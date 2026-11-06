package inkspace.runtime

import inkspace.core.codec.WriteSet
import inkspace.core.error.FeatureError
import inkspace.core.error.StartError
import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.SpaceId
import inkspace.core.id.TransactionId
import inkspace.core.ink.BrushSpec
import inkspace.core.ink.EffectiveInkToolMode
import inkspace.core.ink.GestureDecision
import inkspace.core.ink.GestureSettings
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputContext
import inkspace.core.ink.MultiTapGestureEvent
import inkspace.core.ink.StylusInputPolicy
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.DocumentState
import inkspace.core.model.SessionKey
import inkspace.core.mutation.History
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.objects.ClipboardBundle
import inkspace.core.workspace.*
import inkspace.core.pages.*
import inkspace.core.payload.*
import inkspace.core.mutation.*
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.PlanContext
import inkspace.core.objects.ProtractorOverlayState
import inkspace.core.objects.RulerOverlayState
import inkspace.core.objects.SelectionQuery
import inkspace.core.objects.SelectionSet
import inkspace.core.objects.SnapConfig
import inkspace.core.objects.StrokeData
import inkspace.core.objects.TransferId
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.StrokePayload
import inkspace.core.ports.DurableAck
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.RequestId
import inkspace.core.ports.StagedAssetRef
import inkspace.core.ports.StorageReply
import inkspace.core.ports.StorageRequest
import inkspace.core.render.DraftStrokeSceneItem
import inkspace.core.render.EphemeralOverlayItem
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.AppGateway
import inkspace.core.runtime.EventBatch
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.PlatformPorts
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.runtime.SaveStatus
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.runtime.ViewportState
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.ink.GestureArbiter
import inkspace.ink.InkEngine
import inkspace.ink.InkGeometryPort
import inkspace.objects.ObjectPlanner
import inkspace.objects.SelectionEngine
import inkspace.objects.ShapeRecognizer
import inkspace.store.DocumentStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production runtime coordinator and Engine actor implementing F04:
 * - Single-thread confined Engine, Render, and Store executors + bounded CPU worker pool
 * - Separate bounded queues for input samples, reserved terminal control, durable persistence,
 *   latest-view snapshots, and Main events/errors
 * - Fair quantum scheduling across documents (`no_starvation`)
 * - Continuous durable prefix watermark (`durable_order`)
 * - Generation-checked sessions (`stale_session`) and `SurfaceEpoch` acknowledged teardown
 */
class Runtime private constructor(
    val config: RuntimeConfig,
    val ports: PlatformPorts
) : AppGateway {

    init {
        GestureArbiter.shapeRecognizer = ShapeRecognizer::recognize
    }

    private class NotebookToolPath {
        val xs=FloatArray(64000);val ys=FloatArray(64000);var count=0
        fun add(x:Float,y:Float):Boolean {if(count==xs.size)return false;xs[count]=x;ys[count]=y;count++;return true}
        fun points()=List(count) { DocPoint(xs[it].toDouble(),ys[it].toDouble()) }
    }

    private class SessionContext(
        val session: SessionKey,
        val state: DocumentState,
        val history: History,
        val ownedStore: DocumentStore?,
        var latestSnapshot: DocumentSnapshot,
        var viewport: ViewportState,
        var appliedRevision: Long,
        var durableRevision: Long,
        var lastError: String? = null,
        var isOverflowed: Boolean = false
    ) {
        val openedDurableRevision = durableRevision
        val pendingCommandQueue = ArrayDeque<AppCommand>()
        val pendingInkBatchQueue = ArrayDeque<InputBatch>()
        val inkEngine = InkEngine()
        var activeBrush: BrushSpec = BrushSpec()
        var inputPolicy: StylusInputPolicy = StylusInputPolicy()
        var gestureSettings: GestureSettings = GestureSettings()
        var snapConfig: SnapConfig = SnapConfig()
        var rulerOverlay: RulerOverlayState? = null
        var protractorOverlay: ProtractorOverlayState? = null
        val activeDraftStrokes = ConcurrentHashMap<Int, DraftStrokeSceneItem>()
        val activeEphemeralOverlays = ConcurrentHashMap<Int, EphemeralOverlayItem>()
        var currentSelectionSet: SelectionSet = SelectionSet()
        var notebookTool: String = "pen"
        var notebookSettings: EditorSettings = EditorSettings()
        var activeNotebookLayer: LayerId? = null
        val toolPaths = HashMap<Int,NotebookToolPath>()
        val inFlightReservations = LinkedHashMap<Long, PersistReservation>()
        val outOfOrderAckedRevisions = HashSet<Long>()
        var closed: Boolean = false

        fun computeSaveStatus(): SaveStatus {
            val pendingCount = inFlightReservations.size
            val pendingBytes = inFlightReservations.values.sumOf { it.estimatedBytes }
            val kind = when {
                isOverflowed -> SaveStatusKind.PENDING_OVERFLOW
                lastError != null -> SaveStatusKind.UNSAVED_ERROR
                durableRevision < appliedRevision || pendingCount > 0 -> SaveStatusKind.SAVING
                else -> SaveStatusKind.SAVED
            }
            return SaveStatus(
                session = session,
                status = kind,
                appliedRevision = Revision(appliedRevision),
                durableRevision = Revision(durableRevision),
                pendingCount = pendingCount,
                pendingBytes = pendingBytes,
                lastError = lastError
            )
        }
    }

    val governor = MemoryGovernor(
        maxRenderSnapshots = config.maxRenderSnapshots,
        maxWarmSessions = config.maxWarmSessions,
        workerThreads = config.workerThreads,
        customCaps = mapOf(
            inkspace.core.runtime.MemoryCategory.INPUT_IPC_PERSISTENCE_QUEUES to config.totalQueueByteCap
        )
    )

    val defaultGeometryPort: GeometryPort = ports.geometryPort ?: InkGeometryPort()

    internal val queues = RuntimeQueues(config, governor)

    private val engineExecutor = ConfinedSingleThreadExecutor("tn-engine-actor")
    private val renderExecutor = ConfinedSingleThreadExecutor("tn-render-exec")
    private val storeExecutor = ConfinedSingleThreadExecutor("tn-store-writer")

    private val requestIdGen = AtomicLong(1L)
    private val pauseEngineLoop = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val terminatedLatch = CountDownLatch(1)
    private val completedPendingAtShutdown = AtomicLong(0L)

    private val latestEpochByDocument = ConcurrentHashMap<DocumentId, Long>()
    private val activeSessions = ConcurrentHashMap<SessionKey, SessionContext>()
    private val globalControlQueue = ArrayDeque<AppCommand>()
    private val roundRobinOrder = ArrayList<SessionKey>()

    @Volatile private var androidFeatures: AndroidNotebookFeatures? = null
    private val publishedNotebooks = ConcurrentHashMap<SessionKey,NotebookDocumentState>()
    private var notebookClipboard: inkspace.core.objects.ClipboardBundle? = null
    private val publishedScenes = ConcurrentHashMap<SessionKey,inkspace.core.render.Scene>()

    private val publishedSnapshots = ConcurrentHashMap<SessionKey, DocumentSnapshot>()
    private val publishedSaveStatuses = ConcurrentHashMap<SessionKey, SaveStatus>()
    private val publishedViewports = ConcurrentHashMap<SessionKey, ViewportState>()

    companion object {
        fun start(
            config: RuntimeConfig,
            ports: PlatformPorts = PlatformPorts()
        ): Outcome<Runtime, StartError> {
            val rootDir = File(config.storageRootPath)
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                return Outcome.Failure(
                    StartError.StorageInitFailed("Cannot create storageRootPath: ${config.storageRootPath}")
                )
            }
            return Outcome.Success(Runtime(config, ports))
        }
    }

    override fun trySend(command: AppCommand): Outcome<RequestId, SubmitError> {
        if (shuttingDown.get()) {
            return Outcome.Failure(SubmitError.ShutdownInProgress("Runtime is shutting down"))
        }

        // Validate session epoch upfront for session-targeted commands
        val targetSession = sessionOf(command)
        if (targetSession != null) {
            val latestEpoch = latestEpochByDocument[targetSession.document]
            val ctx = activeSessions[targetSession]
            if (latestEpoch == null || latestEpoch != targetSession.epoch.value || ctx == null || ctx.closed) {
                return Outcome.Failure(
                    SubmitError.StaleSession(
                        session = "${targetSession.document.value}@${targetSession.epoch.value}",
                        reason = "Session is closed or superseded by epoch ${latestEpoch ?: -1L}"
                    )
                )
            }
        }

        when (command) {
            is AppCommand.SubmitInputBatch -> {
                val res = queues.enqueueInputBatch(command.batch)
                if (res is Outcome.Failure) return res
            }
            is AppCommand.SubmitControl -> {
                if (command.action.isTerminal) {
                    val res = queues.enqueueTerminalControl(command)
                    if (res is Outcome.Failure) return res
                } else {
                    synchronized(this) {
                        val ctx = activeSessions[command.session]
                            ?: return Outcome.Failure(
                                SubmitError.StaleSession(command.session.toString(), "Session not active")
                            )
                        ctx.pendingCommandQueue.addLast(command)
                    }
                }
            }
            is AppCommand.SubmitEdit -> {
                val ctx = activeSessions[command.session]
                    ?: return Outcome.Failure(
                        SubmitError.StaleSession(command.session.toString(), "Session not active")
                    )
                val reserveRes = queues.tryReservePersistAndAck(command.session, command.estimatedBytes)
                when (reserveRes) {
                    is Outcome.Failure -> {
                        synchronized(this) {
                            ctx.isOverflowed = true
                            val status = ctx.computeSaveStatus()
                            publishedSaveStatuses[ctx.session] = status
                            queues.emitMainEvent(AppEvent.SaveStatusChanged(status))
                        }
                        return reserveRes
                    }
                    is Outcome.Success -> {
                        val reservation = reserveRes.value
                        synchronized(this) {
                            ctx.isOverflowed = false
                            val nextRevGuess = ctx.appliedRevision + ctx.pendingCommandQueue.count { it is AppCommand.SubmitEdit } + 1L
                            ctx.inFlightReservations[nextRevGuess] = reservation
                            ctx.pendingCommandQueue.addLast(command)
                        }
                    }
                }
            }
            else -> {
                synchronized(this) {
                    if (targetSession != null) {
                        val ctx = activeSessions[targetSession]
                            ?: return Outcome.Failure(
                                SubmitError.StaleSession(targetSession.toString(), "Session not active")
                            )
                        ctx.pendingCommandQueue.addLast(command)
                    } else {
                        globalControlQueue.addLast(command)
                    }
                }
            }
        }

        val reqId = RequestId(requestIdGen.getAndIncrement())
        scheduleEngineStepIfNotPaused()
        return Outcome.Success(reqId)
    }

    override fun pollEvents(max: Int): EventBatch = queues.pollEvents(max)

    override fun acknowledgeError(error: AppEvent.ErrorEvent) {
        queues.acknowledgeError(error)
    }

    override fun latestSnapshot(session: SessionKey): DocumentSnapshot? = publishedSnapshots[session]

    override fun latestSaveStatus(session: SessionKey): SaveStatus? = publishedSaveStatuses[session]

    override fun latestViewport(session: SessionKey): ViewportState? = publishedViewports[session]

    fun activeDraftStrokes(session: SessionKey): List<DraftStrokeSceneItem> {
        val ctx = activeSessions[session] ?: return emptyList()
        return java.util.List.copyOf(ctx.activeDraftStrokes.values)
    }

    fun activeEphemeralOverlays(session: SessionKey): List<EphemeralOverlayItem> {
        val ctx = activeSessions[session] ?: return emptyList()
        return java.util.List.copyOf(ctx.activeEphemeralOverlays.values)
    }

    fun configureInkSession(
        session: SessionKey,
        brush: BrushSpec,
        policy: StylusInputPolicy = StylusInputPolicy()
    ) {
        engineExecutor.submit {
            val ctx = activeSessions[session] ?: return@submit
            ctx.activeBrush = brush
            ctx.inputPolicy = policy
        }.get(5, TimeUnit.SECONDS)
    }

    /**
     * Submits a handwriting [InputBatch] (`RealSample` + optional `PredictedSample`) to the Engine actor,
     * updating live draft strokes, ephemeral prediction/hover overlays, or committing the finished stroke
     * through `validatePlan` -> `applyPlan` -> `History` -> `DocumentStore` on `UP`.
     */
    fun submitInkBatch(batch: InputBatch): Outcome<RequestId, SubmitError> {
        if (shuttingDown.get()) {
            return Outcome.Failure(SubmitError.ShutdownInProgress("Runtime is shutting down"))
        }
        val latestEpoch = latestEpochByDocument[batch.session.document]
        val ctx = activeSessions[batch.session]
        if (latestEpoch == null || latestEpoch != batch.session.epoch.value || ctx == null || ctx.closed) {
            return Outcome.Failure(
                SubmitError.StaleSession(
                    session = "${batch.session.document.value}@${batch.session.epoch.value}",
                    reason = "Session is closed or superseded by epoch ${latestEpoch ?: -1L}"
                )
            )
        }
        synchronized(this) {
            ctx.pendingInkBatchQueue.addLast(batch)
        }
        val reqId = RequestId(requestIdGen.getAndIncrement())
        scheduleEngineStepIfNotPaused()
        return Outcome.Success(reqId)
    }

    /**
     * Executes a spatial + precise geometric [SelectionQuery] against the session's current state
     * on the Engine actor, using [defaultGeometryPort] (`InkGeometryPort`) injected as [GeometryPort].
     */
    fun selectObjects(
        session: SessionKey,
        query: SelectionQuery
    ): Outcome<SelectionSet, FeatureError> {
        return engineExecutor.submit<Outcome<SelectionSet, FeatureError>> {
            val ctx = activeSessions[session]
                ?: return@submit Outcome.Failure(FeatureError.EntityNotFound(session.document.value))
            val out = SelectionEngine.select(
                read = ctx.state,
                current = ctx.currentSelectionSet,
                query = query,
                geometryPort = defaultGeometryPort
            )
            if (out is Outcome.Success) {
                val sel = out.value
                ctx.currentSelectionSet = sel
                ctx.viewport = ctx.viewport.copy(selectedObjectIds = sel.frozenSelectedIds)
                publishedViewports[session] = ctx.viewport
                val selBounds = sel.bounds
                if (selBounds != null && !sel.isEmpty) {
                    ctx.activeEphemeralOverlays[20_000] = EphemeralOverlayItem.SelectionBounds(selBounds)
                } else {
                    ctx.activeEphemeralOverlays.remove(20_000)
                }
                queues.emitMainEvent(AppEvent.ViewportChanged(ctx.viewport))
            }
            out
        }.get(5, TimeUnit.SECONDS)
    }

    /**
     * Plans an [ObjectAction] via [ObjectPlanner.plan] using [defaultGeometryPort] (`InkGeometryPort`)
     * and submits the resulting [inkspace.core.mutation.EditPlan] through the unified `SubmitEdit` pipeline.
     */
    fun submitObjectAction(
        session: SessionKey,
        action: ObjectAction,
        gestureId: GestureId = GestureId("gesture-obj-${requestIdGen.get()}")
    ): Outcome<RequestId, FeatureError> = submitPlannedAction(session, gestureId) { read, ctx ->
        ObjectPlanner.plan(read, action, ctx)
    }

    fun submitPageAction(session: SessionKey, action: inkspace.pages.PageAction,
        gestureId: GestureId = GestureId("page-${requestIdGen.get()}")) =
        submitPlannedAction(session, gestureId) { read, ctx -> inkspace.pages.PagePlanner.plan(read, action, ctx) }

    fun submitTextAction(session: SessionKey, action: inkspace.text.TextAction,
        gestureId: GestureId = GestureId("text-${requestIdGen.get()}")) =
        submitPlannedAction(session, gestureId) { read, ctx -> inkspace.text.TextPlanner.plan(read, action, ctx) }

    fun submitContentAction(session: SessionKey, action: inkspace.content.ContentAction,
        gestureId: GestureId = GestureId("content-${requestIdGen.get()}")) =
        submitPlannedAction(session, gestureId) { read, ctx -> inkspace.content.ContentPlanner.plan(read, action, ctx) }

    private fun submitPlannedAction(
        session: SessionKey,
        gestureId: GestureId,
        planner: (inkspace.core.read.DocumentRead, PlanContext) -> Outcome<inkspace.core.mutation.EditPlan, FeatureError>
    ): Outcome<RequestId, FeatureError> {
        val planOut = engineExecutor.submit<Outcome<inkspace.core.mutation.EditPlan, FeatureError>> {
            val ctx = activeSessions[session]
                ?: return@submit Outcome.Failure(FeatureError.EntityNotFound(session.document.value))
            val pageId = ctx.viewport.activePageId ?: ctx.state.pages.firstOrNull()?.id ?: PageId("page-1")
            val spaceId = ctx.state.getPage(pageId)?.spaceId ?: SpaceId("space-1")
            val layerId = ctx.activeNotebookLayer?.takeIf { id -> ctx.state.getLayer(id)?.let { it.pageId==pageId && !it.isLocked && it.isVisible } == true }
                ?: ctx.state.layers.firstOrNull { it.pageId == pageId && !it.isLocked && it.isVisible }?.id
                ?: ctx.state.layers.firstOrNull()?.id
                ?: LayerId("layer-1")
            val planCtx = PlanContext(
                activeSpaceId = spaceId,
                activePageId = pageId,
                activeLayerId = layerId,
                enteredGroupId = ctx.currentSelectionSet.enteredGroupId,
                gestureId = gestureId,
                geometryPort = defaultGeometryPort,
                limits = config.limits,
                snapConfig = ctx.snapConfig
            )
            planner(ctx.state, planCtx)
        }.get(5, TimeUnit.SECONDS)

        val plan = when (planOut) {
            is Outcome.Failure -> return planOut
            is Outcome.Success -> planOut.value
        }
        val submitRes = trySend(
            AppCommand.SubmitEdit(
                session = session,
                plan = plan,
                estimatedBytes = 1024L
            )
        )
        return when (submitRes) {
            is Outcome.Failure -> Outcome.Failure(FeatureError.InvalidParameters(submitRes.error.toString()))
            is Outcome.Success -> submitRes
        }
    }

    fun reportFeatureError(session:SessionKey?,error:FeatureError) {
        queues.emitMainEvent(AppEvent.ErrorEvent(session,"FEATURE_ERROR",error.toString()))
    }
    fun configureAndroid(context: android.content.Context) {
        synchronized(this) { if(androidFeatures==null) androidFeatures=AndroidNotebookFeatures(this,context.applicationContext) }
    }
    fun bindDrawingSurface(session:SessionKey,surface:android.view.Surface) { androidFeatures?.bind(session,surface) }
    fun unbindDrawingSurface(session:SessionKey) { androidFeatures?.unbind(session) }
    fun createTextEditorView(context:android.content.Context,session:SessionKey,id:String?):android.view.View? =
        androidFeatures?.createTextEditorView(context,session,id?.let(::ObjectId))
    fun sceneForRendering(session:SessionKey):inkspace.core.render.Scene? = publishedScenes[session]
    fun notebookState(session:SessionKey):NotebookDocumentState = publishedNotebooks[session] ?: NotebookDocumentState(snapshot=latestSnapshot(session))
    fun notebookThumbnail(documentId:String,maxEdge:Int):Outcome<NotebookThumbnail,FeatureError> =
        androidFeatures?.thumbnail(documentId,maxEdge) ?: Outcome.Failure(FeatureError.InvalidParameters("Android renderer unavailable"))

    private fun publishNotebook(ctx:SessionContext) {
        val page=ctx.viewport.activePageId ?: ctx.state.pages.firstOrNull()?.id
        val layer=ctx.activeNotebookLayer?.takeIf { ctx.state.getLayer(it)?.pageId==page } ?: ctx.state.layers.firstOrNull { it.pageId==page && it.isVisible && !it.isLocked }?.id
        val outline=ArrayList<NotebookOutline>()
        fun visit(nodes:List<OutlineNode>,pageId:String,parent:String?,depth:Int) {
            nodes.forEach { outline += NotebookOutline(it.id,it.title,pageId,parent,depth);visit(it.children,pageId,it.id,depth+1) }
        }
        ctx.state.pages.forEach { visit(it.metadata.outline,it.id.value,null,0) }
        publishedNotebooks[ctx.session]=NotebookDocumentState(ctx.latestSnapshot,page?.value,layer?.value,
            java.util.Set.copyOf(ctx.currentSelectionSet.frozenSelectedIds.map { it.value }),ctx.notebookTool,
            java.util.List.copyOf(outline),java.util.Set.copyOf(ctx.state.pages.filter { it.metadata.bookmarked }.map { it.id.value }),ctx.notebookSettings)
        publishedScenes[ctx.session]=inkspace.core.render.Scene(snapshot=ctx.latestSnapshot,
            activeSpaceId=page?.let { ctx.state.getPage(it)?.spaceId } ?: SpaceId("space-1"),camera=ctx.viewport.camera,
            viewportWidthPx=ctx.viewport.widthPx.coerceAtLeast(1),viewportHeightPx=ctx.viewport.heightPx.coerceAtLeast(1),
            surfaceEpoch=ctx.viewport.surfaceEpoch.takeIf { it.value>0 } ?: inkspace.core.id.SurfaceEpoch(1),
            draftStrokes=ctx.activeDraftStrokes.values.toList(),ephemeralOverlays=ctx.activeEphemeralOverlays.values.toList())
        androidFeatures?.refresh(ctx.session)
    }

    private fun changeNotebookView(session:SessionKey,change:(SessionContext)->Unit):Outcome<RequestId,FeatureError> = try {
        engineExecutor.submit<Outcome<RequestId,FeatureError>> {
            val ctx=activeSessions[session] ?: return@submit Outcome.Failure(FeatureError.EntityNotFound(session.document.value))
            change(ctx);publishNotebook(ctx)
            Outcome.Success(RequestId(requestIdGen.getAndIncrement()))
        }.get(5,TimeUnit.SECONDS)
    } catch(e:Exception) { Outcome.Failure(FeatureError.InvalidParameters(e.cause?.message ?: e.message ?: "View command failed")) }

    fun performNotebookAction(session:SessionKey,action:NotebookAction):Outcome<RequestId,FeatureError> = try {
        val snapshot=latestSnapshot(session) ?: return Outcome.Failure(FeatureError.EntityNotFound(session.document.value))
        val view=notebookState(session)
        if(latestViewport(session)?.isReadOnly==true && action !is NotebookAction.Tool && action !is NotebookAction.Settings && action !is NotebookAction.SelectPage && action !is NotebookAction.SelectLayer)
            return Outcome.Failure(FeatureError.LockedTarget(session.document.value,"Read only"))
        val pageId=view.activePageId?.let(::PageId) ?: snapshot.pages.first().id
        val page=snapshot.getPage(pageId) ?: snapshot.pages.first()
        val prefix="copy-"+java.util.UUID.randomUUID().toString()
        when(action) {
            is NotebookAction.Tool -> changeNotebookView(session) { ctx ->
                require(action.id in setOf("pen","fountain","pencil","brush","highlighter","eraser","lasso","shape","ruler","text","image","sticky","tape")) { "Unknown tool" }
                ctx.notebookTool=action.id;ctx.notebookSettings=action.settings
                ctx.activeBrush=ctx.activeBrush.copy(kind=when(action.id) { "highlighter"->inkspace.core.ink.BrushKind.HIGHLIGHTER;"fountain"->inkspace.core.ink.BrushKind.FOUNTAIN;"pencil"->inkspace.core.ink.BrushKind.PENCIL;"brush"->inkspace.core.ink.BrushKind.BRUSH;else->inkspace.core.ink.BrushKind.BALL },
                    colorArgb=action.settings.penColor,baseWidthPt=action.settings.penWidthPt)
                if(action.id=="ruler") ctx.rulerOverlay=RulerOverlayState(isVisible=true)
            }
            is NotebookAction.Settings -> changeNotebookView(session) { it.notebookSettings=action.settings;it.activeBrush=it.activeBrush.copy(colorArgb=action.settings.penColor,baseWidthPt=action.settings.penWidthPt) }
            is NotebookAction.SelectPage -> changeNotebookView(session) { ctx ->
                val selected=requireNotNull(ctx.state.getPage(PageId(action.pageId))) { "Page missing" }
                ctx.viewport=ctx.viewport.copy(activePageId=selected.id);publishedViewports[session]=ctx.viewport;ctx.activeNotebookLayer=null
            }
            is NotebookAction.SelectLayer -> changeNotebookView(session) { ctx ->
                val selected=requireNotNull(ctx.state.getLayer(LayerId(action.layerId))) { "Layer missing" }
                require(!selected.isLocked && selected.isVisible) { "Layer hidden or locked" };ctx.activeNotebookLayer=selected.id
                ctx.viewport=ctx.viewport.copy(activePageId=selected.pageId);publishedViewports[session]=ctx.viewport
            }
            is NotebookAction.Page -> {
                val target=action.pageId?.let(::PageId) ?: pageId
                val current=requireNotNull(snapshot.getPage(target))
                val command=when(action.command) {
                    "add" -> { val id=PageId("page-"+java.util.UUID.randomUUID());val space=SpaceId("space-"+java.util.UUID.randomUUID())
                        val defaults=action.defaults ?: NotebookDefaults(widthPt=current.widthPt,heightPt=current.heightPt)
                        inkspace.pages.PageAction.Add(inkspace.core.model.PageDescriptor(id,space,defaults.widthPt,defaults.heightPt,metadataForDefaults(defaults)),
                            inkspace.core.model.LayerDescriptor(LayerId("layer-"+java.util.UUID.randomUUID()),id,"Layer 1"),action.index) }
                    "duplicate" -> inkspace.pages.PageAction.Duplicate(target,prefix,action.index)
                    "delete" -> inkspace.pages.PageAction.Delete(target)
                    "clear" -> inkspace.pages.PageAction.Clear(target)
                    "rotate" -> inkspace.pages.PageAction.Rotate(target,90)
                    "resize","format" -> inkspace.pages.PageAction.Resize(target,action.widthPt ?: current.widthPt,action.heightPt ?: current.heightPt,inkspace.pages.ResizePolicy.valueOf(action.policy))
                    "background" -> inkspace.pages.PageAction.SetMetadata(target,metadataForDefaults(action.defaults ?: NotebookDefaults()).copy(bookmarked=current.metadata.bookmarked,outline=current.metadata.outline,frames=current.metadata.frames))
                    "up","down" -> { val ids=snapshot.pages.map { it.id }.toMutableList();val old=ids.indexOf(target);val next=(old+if(action.command=="up")-1 else 1).coerceIn(0,ids.lastIndex);java.util.Collections.swap(ids,old,next);inkspace.pages.PageAction.Reorder(ids) }
                    else -> throw IllegalArgumentException("Unknown page command")
                }
                submitPageAction(session,command)
            }
            is NotebookAction.Layer -> {
                val target=action.layerId?.let(::LayerId) ?: view.activeLayerId?.let(::LayerId) ?: snapshot.layers.first { it.pageId==pageId }.id
                val layer=requireNotNull(snapshot.getLayer(target))
                val command=when(action.command) {
                    "add" -> inkspace.pages.PageAction.AddLayer(inkspace.core.model.LayerDescriptor(LayerId("layer-"+java.util.UUID.randomUUID()),pageId,action.name ?: "Layer"),action.index)
                    "duplicate" -> inkspace.pages.PageAction.DuplicateLayer(target,prefix)
                    "delete" -> inkspace.pages.PageAction.DeleteLayer(target)
                    "lock" -> inkspace.pages.PageAction.LockLayer(target,action.locked ?: !layer.isLocked)
                    "style" -> inkspace.pages.PageAction.StyleLayer(target,action.opacity ?: layer.opacity,action.visible ?: layer.isVisible,action.name)
                    "move-selection" -> inkspace.pages.PageAction.MoveObjects(view.selectedIds.map(::ObjectId).toSet(),target)
                    "merge" -> inkspace.pages.PageAction.MergeLayers(target,action.targetLayerId?.let(::LayerId) ?: snapshot.layers.last { it.pageId==layer.pageId && it.id!=target }.id)
                    "group" -> inkspace.pages.PageAction.GroupLayers(setOfNotNull(target,action.targetLayerId?.let(::LayerId)),prefix)
                    "up","down" -> { val ids=snapshot.layers.map { it.id }.toMutableList();val old=ids.indexOf(target);val next=(old+if(action.command=="up")-1 else 1).coerceIn(0,ids.lastIndex);java.util.Collections.swap(ids,old,next);inkspace.pages.PageAction.ReorderLayers(ids) }
                    else -> throw IllegalArgumentException("Unknown layer command")
                };submitPageAction(session,command)
            }
            is NotebookAction.Bookmark -> { val target=requireNotNull(snapshot.getPage(PageId(action.pageId)));submitPageAction(session,inkspace.pages.PageAction.SetMetadata(target.id,target.metadata.copy(bookmarked=action.enabled))) }
            is NotebookAction.Outline -> {
                val target=requireNotNull(snapshot.getPage(action.pageId?.let(::PageId) ?: pageId))
                var found=false
                fun edit(nodes:List<OutlineNode>,parent:String?):List<OutlineNode> {
                    val result=nodes.mapNotNull { node ->
                        if(node.id==action.id) { found=true;when(action.command) { "delete"->null;"rename"->node.copy(title=action.title);else->node.copy(children=edit(node.children,node.id)) } }
                        else node.copy(children=edit(node.children,node.id))
                    }.toMutableList()
                    if(action.command=="add" && parent==action.parentId) { found=true;result.add(action.index.takeIf { it in 0..result.size } ?: result.size,OutlineNode(action.id ?: java.util.UUID.randomUUID().toString(),action.title)) }
                    if(action.command in setOf("up","down")) { val old=result.indexOfFirst { it.id==action.id };if(old>=0) java.util.Collections.swap(result,old,(old+if(action.command=="up")-1 else 1).coerceIn(0,result.lastIndex)) }
                    return result
                }
                val outline=edit(target.metadata.outline,null);require(found) { "Outline node or parent missing" }
                submitPageAction(session,inkspace.pages.PageAction.SetMetadata(target.id,target.metadata.copy(outline=outline)))
            }
            is NotebookAction.InsertText -> submitTextAction(session,inkspace.text.TextAction.Create(ObjectId("text-"+java.util.UUID.randomUUID()),TextPayload(action.text),DocRect(24.0,24.0,(page.widthPt-24.0).coerceAtLeast(48.0),160.0)))
            is NotebookAction.Selection -> {
                val ids=view.selectedIds.map(::ObjectId).toSet();require(ids.isNotEmpty()) { "Select objects first" }
                when(action.command) {
                    "copy" -> { val copied=ObjectPlanner.copyToClipboard(snapshot,ids);if(copied is Outcome.Failure) copied else {notebookClipboard=copied.getOrNull();Outcome.Success(RequestId(requestIdGen.getAndIncrement()))} }
                    "lock" -> submitPlannedAction(session,GestureId(prefix)) { read,_ -> Outcome.Success(EditPlan(read.version,mutations=ids.map { SetObjectLock(it,true) },gesture=GestureId(prefix),label=inkspace.core.id.UndoLabel("Lock"))) }
                    else -> { val objectAction=when(action.command) {
                        "delete" -> ObjectAction.Delete(ids)
                        "duplicate" -> ObjectAction.Duplicate(ids,idPrefix=prefix)
                        "group" -> ObjectAction.Group(ids,ObjectId(prefix))
                        "ungroup" -> ObjectAction.Ungroup(ids)
                        "front" -> ObjectAction.ChangeZOrder(ids,inkspace.core.objects.ZOrderCommand.BRING_TO_FRONT)
                        "back" -> ObjectAction.ChangeZOrder(ids,inkspace.core.objects.ZOrderCommand.SEND_TO_BACK)
                        "move" -> ObjectAction.Move(ids,action.dx,action.dy)
                        "color","width" -> ObjectAction.ApplyStyle(ids,inkspace.core.objects.StylePatch(colorArgb=action.color,strokeWidthPt=action.widthPt))
                        else -> throw IllegalArgumentException("Unknown selection command")
                    };submitObjectAction(session,objectAction) }
                }
            }
            is NotebookAction.ImportResource -> androidFeatures?.importResource(session,android.net.Uri.parse(action.uri),action.mimeType,action.asDocument) ?: Outcome.Failure(FeatureError.InvalidParameters("Android import unavailable"))
        }
    } catch(e:Exception) { Outcome.Failure(FeatureError.InvalidParameters(e.cause?.message ?: e.message ?: "Notebook command failed")) }

    private fun metadataForDefaults(defaults:NotebookDefaults):PageMetadata {
        val grid=when(defaults.paper.lowercase()) { "ruled","lined"->PaperGrid.RULED;"square","squared","grid"->PaperGrid.SQUARE;"dotted","dot","dots"->PaperGrid.DOT;"cornell"->PaperGrid.RULED;"millimeter"->PaperGrid.MILLIMETER;"isometric"->PaperGrid.ISOMETRIC;else->PaperGrid.BLANK }
        return PageMetadata(backgroundColor=defaults.paperColor,cover=defaults.cover,template=TemplateDescriptor(defaults.paper,1,defaults.paper,GridSpec(grid)))
    }

    fun snapshotForThumbnail(documentId:String):DocumentSnapshot? {
        require(documentId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        publishedSnapshots.entries.firstOrNull { it.key.document.value==documentId }?.let { return it.value }
        val store=DocumentStore(File(config.storageRootPath))
        val id=DocumentId(documentId)
        return try {
            if(store.execute(StorageRequest.Open(id,false)) is Outcome.Failure) null else store.currentState(id)?.snapshot()
        } finally {store.execute(StorageRequest.Close(id))}
    }

    fun copyNotebook(sourceId:String,targetId:String,defaults:NotebookDefaults):Outcome<Unit,FeatureError> = try {
        require(sourceId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && targetId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && sourceId!=targetId)
        val source=requireNotNull(snapshotForThumbnail(sourceId)) { "Source notebook unavailable" }
        val targetDir=File(config.storageRootPath,"documents/$targetId")
        require(!File(targetDir,"document.sqlite").exists() && !targetDir.exists()) { "Target already exists" }
        for(asset in source.referencedAssets()) {
            val hex=asset.value.removePrefix("sha256:");require(hex.matches(Regex("[0-9a-f]{64}")))
            val from=File(config.storageRootPath,"documents/$sourceId/assets/${hex.take(2)}/$hex")
            val to=File(targetDir,"assets/${hex.take(2)}/$hex");to.parentFile?.mkdirs();from.inputStream().use { input -> java.io.FileOutputStream(to).use { output -> input.copyTo(output,65536);output.fd.sync() } }
        }
        val store=DocumentStore(File(config.storageRootPath));val id=DocumentId(targetId)
        try {
            require(store.execute(StorageRequest.Open(id,true)).isSuccess)
            val initial=requireNotNull(store.currentState(id));val mutations=ArrayList<Mutation>()
            for(page in source.pages) {
                if(initial.getPage(page.id)!=null) mutations+=UpdatePageDescriptor(page)
                else mutations+=AddPage(page)
            }
            for(layer in source.layers) {
                if(initial.getLayer(layer.id)!=null) mutations+=UpdateLayerDescriptor(layer.copy(isLocked=false))
                else mutations+=AddLayer(layer.copy(isLocked=false))
            }
            source.orderedSceneObjects().forEach { obj -> mutations+=InsertObject(obj.space,obj.layer,obj.id,obj.payload,obj.bounds,obj.transform,obj.style,obj.isLocked,obj.version) }
            initial.layers.filter { layer->source.layers.none { it.id==layer.id } }.forEach { mutations+=RemoveLayer(it.id) }
            initial.pages.filter { page->source.pages.none { it.id==page.id } }.forEach { mutations+=RemovePage(it.id) }
            source.layers.filter { it.isLocked }.forEach { mutations+=SetLayerLock(it.id,true) }
            val plan=EditPlan(initial.version,mutations=mutations,gesture=GestureId("copy"),label=inkspace.core.id.UndoLabel("Copy notebook"))
            val valid=validatePlan(initial,plan,config.limits).getOrNull() ?: throw IllegalArgumentException("Copy plan invalid")
            val applied=applyPlan(initial,valid).getOrNull() ?: throw IllegalArgumentException("Copy failed")
            require(store.execute(StorageRequest.Commit(applied.revision,transactionId=inkspace.core.id.TransactionId("copy-$targetId"),writeSet=applied.writeSet)).isSuccess)
            Outcome.Success(Unit)
        } finally {store.execute(StorageRequest.Close(id))}
    } catch(e:Exception) {Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Notebook copy failed"))}

    fun submitMultiTap(
        session: SessionKey,
        event: MultiTapGestureEvent
    ): Outcome<GestureDecision, FeatureError> {
        return engineExecutor.submit<Outcome<GestureDecision, FeatureError>> {
            val ctx = activeSessions[session]
                ?: return@submit Outcome.Failure(FeatureError.EntityNotFound(session.document.value))
            val decision = GestureArbiter.arbitrateMultiTap(
                event = event,
                settings = ctx.gestureSettings,
                hasActiveOperation = !ctx.currentSelectionSet.isEmpty,
                isSystemIntercepted = false
            )
            if (decision is GestureDecision.ExecuteUndoRedo) {
                executeUndoRedoInternal(session, isUndo = !decision.proposal.isRedo)
            }
            Outcome.Success(decision)
        }.get(5, TimeUnit.SECONDS)
    }

    fun setGestureSettings(session: SessionKey, settings: GestureSettings) {
        engineExecutor.execute {
            activeSessions[session]?.let { it.gestureSettings = settings }
        }
    }

    fun getGestureSettings(session: SessionKey): GestureSettings {
        return engineExecutor.submit<GestureSettings> {
            activeSessions[session]?.gestureSettings ?: GestureSettings()
        }.get(5, TimeUnit.SECONDS)
    }

    fun setSnapConfig(session: SessionKey, snapConfig: SnapConfig) {
        engineExecutor.execute {
            activeSessions[session]?.let { it.snapConfig = snapConfig }
        }
    }

    fun getSnapConfig(session: SessionKey): SnapConfig {
        return engineExecutor.submit<SnapConfig> {
            activeSessions[session]?.snapConfig ?: SnapConfig()
        }.get(5, TimeUnit.SECONDS)
    }

    fun setRulerOverlay(session: SessionKey, overlay: RulerOverlayState?) {
        engineExecutor.execute {
            activeSessions[session]?.let { it.rulerOverlay = overlay }
        }
    }

    fun getRulerOverlay(session: SessionKey): RulerOverlayState? {
        return engineExecutor.submit<RulerOverlayState?> {
            activeSessions[session]?.rulerOverlay
        }.get(5, TimeUnit.SECONDS)
    }

    fun setProtractorOverlay(session: SessionKey, overlay: ProtractorOverlayState?) {
        engineExecutor.execute {
            activeSessions[session]?.let { it.protractorOverlay = overlay }
        }
    }

    fun getProtractorOverlay(session: SessionKey): ProtractorOverlayState? {
        return engineExecutor.submit<ProtractorOverlayState?> {
            activeSessions[session]?.protractorOverlay
        }.get(5, TimeUnit.SECONDS)
    }

    fun currentSelectionSet(session: SessionKey): SelectionSet? {
        return engineExecutor.submit<SelectionSet?> {
            activeSessions[session]?.currentSelectionSet
        }.get(5, TimeUnit.SECONDS)
    }

    fun copySelectionToClipboard(
        session: SessionKey,
        targets: Set<inkspace.core.id.ObjectId>? = null,
        transferId: TransferId = TransferId("clip-${requestIdGen.getAndIncrement()}")
    ): Outcome<ClipboardBundle, FeatureError> {
        return engineExecutor.submit<Outcome<ClipboardBundle, FeatureError>> {
            val ctx = activeSessions[session]
                ?: return@submit Outcome.Failure(FeatureError.EntityNotFound(session.document.value))
            val effectiveTargets = targets ?: ctx.currentSelectionSet.frozenSelectedIds
            ObjectPlanner.copyToClipboard(ctx.state, effectiveTargets, transferId)
        }.get(5, TimeUnit.SECONDS)
    }

    override fun closeAsync(): Outcome<RequestId, SubmitError> {
        if (!shuttingDown.compareAndSet(false, true)) {
            return Outcome.Failure(SubmitError.ShutdownInProgress("closeAsync already in progress"))
        }
        val reqId = RequestId(requestIdGen.getAndIncrement())
        pauseEngineLoop.set(false)
        engineExecutor.execute {
            drainAllQueuesInternal()
            var ensuredCommits = 0L
            for (ctx in activeSessions.values) {
                flushPendingForSessionInternal(ctx)
                ensuredCommits += ctx.durableRevision-ctx.openedDurableRevision
                ctx.ownedStore?.let { store ->
                    store.execute(StorageRequest.Close(ctx.session.document))
                }
                ctx.closed = true
            }
            completedPendingAtShutdown.set(ensuredCommits)
            val completed=ensuredCommits.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            queues.emitMainEvent(AppEvent.Closed(completed))
            androidFeatures?.close()
            governor.close()
            renderExecutor.close()
            storeExecutor.close()
            engineExecutor.close()
            terminatedLatch.countDown()
        }
        return Outcome.Success(reqId)
    }

    fun deliverDurableAck(ack: DurableAck) {
        queues.enqueueDurableAck(ack)
        scheduleEngineStepIfNotPaused()
    }

    internal fun retainedSnapshotPinCountForTest(): Int = governor.activeRenderSnapshotCount()

    internal fun setPauseEngineLoopForTest(paused: Boolean) {
        pauseEngineLoop.set(paused)
        if (!paused) {
            scheduleEngineStepIfNotPaused()
        }
    }

    internal fun openSessionSyncForTest(documentId: DocumentId): SessionKey {
        val future = engineExecutor.submit {
            openSessionInternal(AppCommand.OpenSession(documentId))
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    internal fun stepSingleQuantumForTest(): List<String> {
        val future = engineExecutor.submit {
            val log = ArrayList<String>()
            executeSingleQuantumInternal(log)
            log
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    internal fun drainEngineLoopForTest() {
        engineExecutor.submit {
            drainAllQueuesInternal()
        }.get(10, TimeUnit.SECONDS)
        if (!storeExecutor.isClosed()) {
            storeExecutor.submit { }.get(10, TimeUnit.SECONDS)
        }
        engineExecutor.submit {
            drainAllQueuesInternal()
        }.get(10, TimeUnit.SECONDS)
    }

    internal fun awaitTerminationForTest(timeoutMs: Long = 5_000L) {
        terminatedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    internal fun closeForTest() {
        if (!shuttingDown.get()) {
            closeAsync()
        }
        awaitTerminationForTest(5_000L)
    }

    private fun scheduleEngineStepIfNotPaused() {
        if (!pauseEngineLoop.get() && !engineExecutor.isClosed()) {
            engineExecutor.execute {
                if (!pauseEngineLoop.get()) {
                    drainAllQueuesInternal()
                }
            }
        }
    }

    private fun drainAllQueuesInternal() {
        var safety = 0
        while (hasMoreWorkInternal() && safety < 10_000) {
            executeSingleQuantumInternal(null)
            safety += 1
        }
    }

    private fun hasMoreWorkInternal(): Boolean {
        synchronized(this) {
            if (queues.hasPendingInputOrControl()) return true
            if (queues.hasPendingDurableAcks()) return true
            if (globalControlQueue.isNotEmpty()) return true
            for (ctx in activeSessions.values) {
                if (ctx.pendingInkBatchQueue.isNotEmpty()) return true
                if (ctx.pendingCommandQueue.isNotEmpty()) return true
            }
            return false
        }
    }

    /**
     * Executes one fair scheduling quantum on the Engine actor thread:
     * 1. Up to 4 input/terminal-control items (preventing pen starvation)
     * 2. All arrived DurableAcks
     * 3. Up to 2 global control commands
     * 4. Up to 2 commands per session in round-robin order
     */
    private fun executeSingleQuantumInternal(traceLog: MutableList<String>?) {
        // 1. High-priority input & terminal control quantum (up to 4 batches/controls)
        for (i in 0 until 4) {
            val item = queues.pollNextInputOrControl() ?: break
            when (item) {
                is ScheduledInputItem.BatchItem -> {
                    item.lease.close()
                    val b = item.batch
                    traceLog?.add("input:${b.session.document.value}:seq-${b.sequenceNumber}")
                    queues.emitMainEvent(
                        AppEvent.InputAccepted(
                            session = b.session,
                            pointerId = b.pointerId,
                            sequenceNumber = b.sequenceNumber,
                            sampleCount = b.sampleCount,
                            action = b.action
                        )
                    )
                }
                is ScheduledInputItem.TerminalControlItem -> {
                    val c = item.command
                    traceLog?.add("control:${c.session.document.value}:seq-${c.sequenceNumber}")
                    if (c.action == InputActionKind.CANCEL) {
                        queues.emitMainEvent(
                            AppEvent.InputDraftCanceled(
                                session = c.session,
                                pointerId = c.pointerId,
                                sequenceNumber = c.sequenceNumber,
                                reason = "Terminal CANCEL control received"
                            )
                        )
                    } else {
                        queues.emitMainEvent(
                            AppEvent.InputAccepted(
                                session = c.session,
                                pointerId = c.pointerId,
                                sequenceNumber = c.sequenceNumber,
                                sampleCount = 0,
                                action = c.action
                            )
                        )
                    }
                }
            }
        }

        // 2. Process all arrived DurableAcks
        while (true) {
            val ack = queues.pollNextDurableAck() ?: break
            processDurableAckInternal(ack)
        }

        // 3. Process up to 2 global control commands
        for (i in 0 until 2) {
            val globalCmd = synchronized(this) {
                if (globalControlQueue.isEmpty()) null else globalControlQueue.removeFirst()
            } ?: break
            processCommandInternal(globalCmd, traceLog)
        }

        // 4. Round-robin up to 4 ink batches and up to 2 commands per session
        val sessionsSnapshot = synchronized(this) { roundRobinOrder.toList() }
        for (sessionKey in sessionsSnapshot) {
            for (inkStep in 0 until 4) {
                val inkBatch = synchronized(this) {
                    val ctx = activeSessions[sessionKey]
                    if (ctx == null || ctx.pendingInkBatchQueue.isEmpty()) null else ctx.pendingInkBatchQueue.removeFirst()
                } ?: break
                processInkBatchInternal(inkBatch)
            }
            for (step in 0 until 2) {
                val cmd = synchronized(this) {
                    val ctx = activeSessions[sessionKey]
                    if (ctx == null || ctx.pendingCommandQueue.isEmpty()) null else ctx.pendingCommandQueue.removeFirst()
                } ?: break
                processCommandInternal(cmd, traceLog)
            }
        }
    }

    private fun processNotebookToolInput(ctx:SessionContext,batch:InputBatch):Boolean {
        if(ctx.notebookTool in setOf("pen","fountain","pencil","brush","highlighter","ruler") || batch.toolType==inkspace.core.ink.InputToolType.FINGER) return false
        if(batch.isEffectiveCancel) {ctx.toolPaths.remove(batch.pointerId);ctx.activeEphemeralOverlays.remove(batch.pointerId);return true}
        if(batch.action==InputActionKind.DOWN) {
            if(ctx.toolPaths.size>=16) {reportFeatureError(ctx.session,FeatureError.InvalidParameters("Too many active pointers"));return true}
            ctx.toolPaths[batch.pointerId]=NotebookToolPath()
        }
        val path=ctx.toolPaths[batch.pointerId] ?: return true
        for(i in 0 until batch.realSampleCount) {
            val camera=ctx.viewport.camera
            if(!path.add(((batch.realX(i)-camera.translateX)/camera.scale).toFloat(),((batch.realY(i)-camera.translateY)/camera.scale).toFloat())) {
                ctx.toolPaths.remove(batch.pointerId);reportFeatureError(ctx.session,FeatureError.InvalidParameters("Tool path exceeds 64000 points"));return true
            }
        }
        if(batch.action!=InputActionKind.UP) return true
        ctx.toolPaths.remove(batch.pointerId)
        if(path.count==0)return true
        val points=path.points()
        val bounds=DocRect(points.minOf { it.x },points.minOf { it.y },points.maxOf { it.x }.let { if(it==points.minOf { p->p.x })it+1 else it },points.maxOf { it.y }.let { if(it==points.minOf { p->p.y })it+1 else it })
        val page=ctx.state.getPage(ctx.viewport.activePageId ?: ctx.state.pages.first().id) ?: return true
        val layer=ctx.activeNotebookLayer?.let(ctx.state::getLayer) ?: ctx.state.layers.firstOrNull { it.pageId==page.id && it.isVisible && !it.isLocked } ?: return true
        val gesture=GestureId("tool-${batch.sequenceNumber}")
        val planCtx=PlanContext(activeSpaceId=page.spaceId,activePageId=page.id,activeLayerId=layer.id,gestureId=gesture,geometryPort=defaultGeometryPort,limits=config.limits)
        if(ctx.notebookTool=="lasso") {
            val query=SelectionQuery(spaceId=page.spaceId,mode=if(points.size>=3)inkspace.core.objects.SelectionMode.FREEHAND else inkspace.core.objects.SelectionMode.RECTANGLE,
                rect=bounds,polygonPoints=if(points.size>=3)points else emptyList())
            val selected=SelectionEngine.select(ctx.state,ctx.currentSelectionSet,query)
            if(selected is Outcome.Success) ctx.currentSelectionSet=selected.value
            else reportFeatureError(ctx.session,(selected as Outcome.Failure).error)
            return true
        }
        if(ctx.viewport.isReadOnly)return true
        val id=ObjectId("tool-"+java.util.UUID.randomUUID())
        val plan=when(ctx.notebookTool) {
            "eraser" -> ObjectPlanner.plan(ctx.state,ObjectAction.Erase(points,inkspace.core.objects.EraserSpec(radiusPt=ctx.notebookSettings.penWidthPt.coerceIn(0.1,500.0))),planCtx)
            "shape" -> ObjectPlanner.plan(ctx.state,ObjectAction.ShapeAction.CreateShape(id,ShapePayload(inkspace.core.payload.ShapeType.Rectangle,ctx.notebookSettings.penColor,strokeWidthPt=ctx.notebookSettings.penWidthPt),bounds),planCtx)
            "sticky" -> inkspace.content.ContentPlanner.plan(ctx.state,inkspace.content.ContentAction.InsertSticky(id,0xFFFFF59D.toInt(),"",bounds),planCtx)
            "tape" -> Outcome.Success(EditPlan(ctx.state.version,mutations=listOf(InsertObject(page.spaceId,layer.id,id,TapePayload(ctx.notebookSettings.penColor,points=points,widthPt=ctx.notebookSettings.penWidthPt.coerceAtLeast(12.0)),bounds)),gesture=gesture,label=inkspace.core.id.UndoLabel("Tape")))
            "text" -> { val hit=ctx.state.queryBounds(bounds).firstOrNull { ctx.state.getObjectPayload(it) is TextPayload };ctx.currentSelectionSet=SelectionSet(hit?.let(::setOf) ?: emptySet());null }
            else -> null
        }
        when(plan) {
            is Outcome.Success -> {executeEditInternal(AppCommand.SubmitEdit(ctx.session,plan.value),null);if(ctx.notebookTool in setOf("shape","sticky","tape")) ctx.notebookTool="lasso"}
            is Outcome.Failure -> reportFeatureError(ctx.session,plan.error)
            null -> Unit
        }
        return true
    }

    private fun processInkBatchInternal(batch: InputBatch) {
        val ctx = activeSessions[batch.session] ?: return
        if(processNotebookToolInput(ctx,batch)) {publishNotebook(ctx);return}
        val activePage = ctx.viewport.activePageId ?: ctx.state.pages.firstOrNull()?.id ?: PageId("page-1")
        val activeLayer = ctx.activeNotebookLayer?.takeIf { ctx.state.getLayer(it)?.pageId==activePage }
            ?: ctx.state.layers.firstOrNull { it.pageId == activePage && it.isVisible && !it.isLocked }?.id
            ?: ctx.state.layers.firstOrNull()?.id
            ?: LayerId("layer-1")
        val activeSpace = ctx.state.pages.firstOrNull { it.id == activePage }?.spaceId ?: SpaceId("space-1")
        val isLayerLocked = ctx.state.isLayerLocked(activeLayer) || ctx.viewport.isReadOnly

        val inputCtx = InputContext(
            docVersion = ctx.state.version,
            activeSpaceId = activeSpace,
            activePageId = activePage,
            activeLayerId = activeLayer,
            isLayerLocked = isLayerLocked,
            camera = ctx.viewport.camera,
            brush = ctx.activeBrush,
            policy = ctx.inputPolicy,
            gestureSettings = ctx.gestureSettings
        )

        val ingestRes = ctx.inkEngine.ingest(batch, inputCtx)
        val out = when (ingestRes) {
            is Outcome.Failure -> {
                ctx.activeDraftStrokes.remove(batch.pointerId)
                ctx.activeEphemeralOverlays.remove(batch.pointerId)
                queues.emitMainEvent(
                    AppEvent.ErrorEvent(
                        session = batch.session,
                        code = "INK_INGEST_ERROR",
                        message = ingestRes.error.toString()
                    )
                )
                return
            }
            is Outcome.Success -> ingestRes.value
        }

        if (out.effectiveToolMode == EffectiveInkToolMode.CANCELED ||
            out.effectiveToolMode == EffectiveInkToolMode.PALM_REJECTED
        ) {
            if (out.effectiveToolMode == EffectiveInkToolMode.CANCELED &&
                batch.action == InputActionKind.CANCEL &&
                !batch.isFlagCanceled
            ) {
                ctx.activeDraftStrokes.clear()
                ctx.activeEphemeralOverlays.clear()
            } else {
                ctx.activeDraftStrokes.remove(batch.pointerId)
                ctx.activeEphemeralOverlays.remove(batch.pointerId)
            }
            queues.emitMainEvent(
                AppEvent.InputDraftCanceled(
                    session = batch.session,
                    pointerId = batch.pointerId,
                    sequenceNumber = batch.sequenceNumber,
                    reason = out.effectiveToolMode.name
                )
            )
            return
        }

        val draftStroke = out.draftStroke
        if (draftStroke != null) {
            ctx.activeDraftStrokes[batch.pointerId] = draftStroke
        } else {
            ctx.activeDraftStrokes.remove(batch.pointerId)
        }

        val overlay = out.predictedOverlay ?: out.hoverOverlay
        if (overlay != null) {
            ctx.activeEphemeralOverlays[batch.pointerId] = overlay
        } else {
            ctx.activeEphemeralOverlays.remove(batch.pointerId)
        }

        publishNotebook(ctx)
        val navDelta = out.navigationDeltaDoc
        if (navDelta != null) {
            val oldCam = ctx.viewport.camera
            val nextCam = oldCam.copy(
                translateX = oldCam.translateX + navDelta.x * oldCam.scale,
                translateY = oldCam.translateY + navDelta.y * oldCam.scale
            )
            updateViewportInternal(ctx.viewport.copy(camera = nextCam))
        }

        queues.emitMainEvent(
            AppEvent.InputAccepted(
                session = batch.session,
                pointerId = batch.pointerId,
                sequenceNumber = batch.sequenceNumber,
                sampleCount = batch.realSampleCount,
                action = batch.action
            )
        )

        val commitPlan = out.commitPlan

        var finalDecision = out.gestureDecision
        if (batch.action == InputActionKind.UP && (finalDecision == null || finalDecision is GestureDecision.FallbackToInk)) {
            val allObjs = ctx.state.snapshot().directory.allResidentObjects()
            val candidateStrokes = allObjs
                .filter { it.payload is StrokePayload }
                .map { it.id to it.bounds }
            val recentLoop = allObjs
                .lastOrNull { (it.payload as? StrokePayload)?.samples?.isNotEmpty() == true }
                ?.let { it.id to it.bounds }
            val strokeData = (commitPlan?.mutations?.firstOrNull() as? inkspace.core.mutation.InsertObject)?.let { ins ->
                (ins.payload as? StrokePayload)?.let { sp ->
                    StrokeData(
                        origin = sp.origin,
                        samples = sp.samples,
                        brushKind = ctx.activeBrush.kind,
                        baseWidthPt = sp.baseWidthPt,
                        colorArgb = sp.color,
                        holdDurationMs = out.holdDurationMs
                    )
                }
            }
            if (strokeData != null) {
                val evaluated = GestureArbiter.arbitrate(
                    stroke = strokeData,
                    ctx = inputCtx,
                    strokeId = ObjectId("stroke-${batch.sequenceNumber}"),
                    candidateErasedStrokes = candidateStrokes,
                    recentClosedLoop = recentLoop
                )
                if (evaluated !is GestureDecision.FallbackToInk && evaluated !is GestureDecision.Ignored) {
                    finalDecision = evaluated
                }
            }
        }

        if (finalDecision is GestureDecision.ExecuteShape) {
            val prop = finalDecision.proposal
            val cand = prop.shapeCandidate
            val shapeId = prop.strokeId
            val shapeAction = ObjectAction.ShapeAction.CreateShape(
                objectId = shapeId,
                payload = cand.payload,
                bounds = cand.bounds
            )
            val planCtx = PlanContext(
                activeSpaceId = activeSpace,
                activePageId = activePage,
                activeLayerId = activeLayer,
                gestureId = GestureId("shape-gesture-${shapeId.value}"),
                geometryPort = defaultGeometryPort,
                limits = config.limits
            )
            val shapePlanOut = ObjectPlanner.plan(ctx.state, shapeAction, planCtx)
            if (shapePlanOut is Outcome.Success) {
                val estBytes = 512L
                val reserveRes = queues.tryReservePersistAndAck(batch.session, estBytes)
                if (reserveRes is Outcome.Success) {
                    ctx.inFlightReservations[ctx.appliedRevision + 1L] = reserveRes.value
                    executeEditInternal(
                        AppCommand.SubmitEdit(
                            session = batch.session,
                            plan = shapePlanOut.value,
                            estimatedBytes = estBytes
                        ),
                        null
                    )
                }
            }
            return
        }

        if (finalDecision is GestureDecision.ExecuteScribbleErase) {
            val erasedIds = finalDecision.proposal.erasedStrokeIds
            if (erasedIds.isNotEmpty()) {
                val deleteAction = ObjectAction.Delete(erasedIds)
                val planCtx = PlanContext(
                    activeSpaceId = activeSpace,
                    activePageId = activePage,
                    activeLayerId = activeLayer,
                    gestureId = GestureId("scribble-erase-${batch.sequenceNumber}"),
                    geometryPort = defaultGeometryPort,
                    limits = config.limits
                )
                val deletePlanOut = ObjectPlanner.plan(ctx.state, deleteAction, planCtx)
                if (deletePlanOut is Outcome.Success) {
                    val estBytes = 512L
                    val reserveRes = queues.tryReservePersistAndAck(batch.session, estBytes)
                    if (reserveRes is Outcome.Success) {
                        ctx.inFlightReservations[ctx.appliedRevision + 1L] = reserveRes.value
                        executeEditInternal(
                            AppCommand.SubmitEdit(
                                session = batch.session,
                                plan = deletePlanOut.value,
                                estimatedBytes = estBytes
                            ),
                            null
                        )
                    }
                }
            }
            return
        }

        if (finalDecision is GestureDecision.ExecuteLassoSelection) {
            val enclosed = finalDecision.proposal.enclosedBounds
            val query = SelectionQuery(
                spaceId = activeSpace,
                mode = inkspace.core.objects.SelectionMode.RECTANGLE,
                rule = inkspace.core.objects.SelectionRule.INTERSECTS,
                rect = enclosed
            )
            val selOut = SelectionEngine.select(ctx.state, ctx.currentSelectionSet, query, defaultGeometryPort)
            if (selOut is Outcome.Success) {
                ctx.currentSelectionSet = selOut.value
            }
            return
        }

        if (finalDecision is GestureDecision.ExecuteUndoRedo) {
            executeUndoRedoInternal(batch.session, isUndo = !finalDecision.proposal.isRedo)
            return
        }

        if (commitPlan != null) {
            val estBytes = batch.byteSize.toLong().coerceAtLeast(256L)
            val reserveRes = queues.tryReservePersistAndAck(batch.session, estBytes)
            when (reserveRes) {
                is Outcome.Failure -> {
                    ctx.isOverflowed = true
                    val status = ctx.computeSaveStatus()
                    publishedSaveStatuses[ctx.session] = status
                    queues.emitMainEvent(AppEvent.SaveStatusChanged(status))
                }
                is Outcome.Success -> {
                    ctx.isOverflowed = false
                    ctx.inFlightReservations[ctx.appliedRevision + 1L] = reserveRes.value
                    executeEditInternal(
                        AppCommand.SubmitEdit(
                            session = batch.session,
                            plan = commitPlan,
                            estimatedBytes = estBytes
                        ),
                        null
                    )
                }
            }
        }
    }

    private fun processCommandInternal(cmd: AppCommand, traceLog: MutableList<String>?) {
        when (cmd) {
            is AppCommand.OpenSession -> openSessionInternal(cmd)
            is AppCommand.CloseSession -> closeSessionInternal(cmd.session)
            is AppCommand.SubmitEdit -> executeEditInternal(cmd, traceLog)
            is AppCommand.Undo -> executeUndoRedoInternal(cmd.session, isUndo = true)
            is AppCommand.Redo -> executeUndoRedoInternal(cmd.session, isUndo = false)
            is AppCommand.UpdateViewport -> updateViewportInternal(cmd.state)
            is AppCommand.AttachSurface -> attachSurfaceInternal(cmd)
            is AppCommand.DetachSurface -> detachSurfaceInternal(cmd)
            is AppCommand.BackgroundFlush -> {
                val ctx = activeSessions[cmd.session] ?: return
                flushPendingForSessionInternal(ctx)
            }
            is AppCommand.TrimMemory -> {
                val reclaimed = governor.trimMemory(cmd.level)
                queues.emitMainEvent(AppEvent.MemoryTrimmed(cmd.level, reclaimed))
            }
            is AppCommand.DeliverJobReply -> {
                val ctx = activeSessions[cmd.session]
                if (ctx != null && !ctx.closed) {
                    queues.emitMainEvent(
                        AppEvent.JobReplyAccepted(
                            session = cmd.session,
                            generation = cmd.generation,
                            resource = cmd.resource
                        )
                    )
                }
            }
            is AppCommand.SubmitInputBatch, is AppCommand.SubmitControl -> {
                // Handled in step 1
            }
        }
    }

    private fun openSessionInternal(cmd: AppCommand.OpenSession): SessionKey {
        val nextEpoch = (latestEpochByDocument[cmd.documentId] ?: 0L) + 1L
        latestEpochByDocument[cmd.documentId] = nextEpoch
        val sessionKey = SessionKey(cmd.documentId, SessionEpoch(nextEpoch))

        val rootDir = File(config.storageRootPath)
        val ownedStore = if (ports.storagePort == null) {
            DocumentStore(rootDir)
        } else {
            null
        }

        val state: DocumentState
        val durableRev: Long
        if (ownedStore != null) {
            val openRes = ownedStore.execute(StorageRequest.Open(cmd.documentId, cmd.createIfMissing))
            val opened = (openRes as? Outcome.Success)?.value as? StorageReply.Opened
            durableRev = opened?.durableRevision?.value ?: 0L
            val loadedSnap = ownedStore.currentState(cmd.documentId)?.snapshot()
            state = if (loadedSnap != null) {
                DocumentState.fromSnapshot(loadedSnap)
            } else {
                DocumentState.createInitial(cmd.documentId)
            }
        } else {
            ports.storagePort?.trySubmit(StorageRequest.Open(cmd.documentId, cmd.createIfMissing))
            durableRev = 0L
            state = DocumentState.createInitial(cmd.documentId)
        }

        val initialSnapshot = state.snapshot()
        governor.pinRenderSnapshot(sessionKey, initialSnapshot)
        governor.touchWarmSession(sessionKey)

        val viewport = cmd.initialViewport?.copy(session = sessionKey)
            ?: ViewportState(session = sessionKey)
        val ctx = SessionContext(
            session = sessionKey,
            state = state,
            history = History(),
            ownedStore = ownedStore,
            latestSnapshot = initialSnapshot,
            viewport = viewport,
            appliedRevision = initialSnapshot.version.revision.value,
            durableRevision = durableRev
        )

        synchronized(this) {
            activeSessions[sessionKey] = ctx
            roundRobinOrder.add(sessionKey)
        }

        val saveStatus = ctx.computeSaveStatus()
        publishedSnapshots[sessionKey] = initialSnapshot
        publishedSaveStatuses[sessionKey] = saveStatus
        publishedViewports[sessionKey] = viewport
        publishNotebook(ctx)

        queues.emitMainEvent(
            AppEvent.SessionOpened(
                session = sessionKey,
                snapshot = initialSnapshot,
                saveStatus = saveStatus,
                viewport = viewport
            )
        )
        return sessionKey
    }

    private fun closeSessionInternal(session: SessionKey) {
        val ctx = synchronized(this) {
            roundRobinOrder.remove(session)
            activeSessions.remove(session)
        } ?: return

        flushPendingForSessionInternal(ctx)
        ctx.closed = true
        publishedNotebooks.remove(session)
        publishedScenes.remove(session)
        publishedSnapshots.remove(session)
        publishedViewports.remove(session)
        publishedSaveStatuses.remove(session)
        androidFeatures?.unbind(session)
        governor.removeWarmSession(session)
        ctx.ownedStore?.let { store ->
            store.execute(StorageRequest.Close(session.document))
        }
        queues.emitMainEvent(
            AppEvent.SessionClosed(
                session = session,
                finalDurableRevision = Revision(ctx.durableRevision)
            )
        )
    }

    private fun executeEditInternal(cmd: AppCommand.SubmitEdit, traceLog: MutableList<String>?) {
        val ctx = activeSessions[cmd.session] ?: return
        traceLog?.add("edit:${cmd.session.document.value}:base-${cmd.plan.base.revision.value}")

        val validatedOutcome = validatePlan(ctx.state, cmd.plan, config.limits)
        val validated = when (validatedOutcome) {
            is Outcome.Failure -> {
                // Release pre-reserved slot if validation fails
                val failedGuessRev = ctx.appliedRevision + 1L
                ctx.inFlightReservations.remove(failedGuessRev)?.close()
                queues.emitMainEvent(
                    AppEvent.ErrorEvent(
                        session = cmd.session,
                        code = "VALIDATION_FAILED",
                        message = validatedOutcome.error.toString()
                    )
                )
                return
            }
            is Outcome.Success -> validatedOutcome.value
        }

        val appliedOutcome = applyPlan(ctx.state, validated)
        val applied = when (appliedOutcome) {
            is Outcome.Failure -> {
                val failedGuessRev = ctx.appliedRevision + 1L
                ctx.inFlightReservations.remove(failedGuessRev)?.close()
                queues.emitMainEvent(
                    AppEvent.ErrorEvent(
                        session = cmd.session,
                        code = "APPLY_FAILED",
                        message = appliedOutcome.error.toString()
                    )
                )
                return
            }
            is Outcome.Success -> appliedOutcome.value
        }

        ctx.history.record(cmd.plan, applied)
        val newRev = applied.revision.value
        ctx.appliedRevision = newRev

        // Ensure reservation is keyed by actual newRev
        if (!ctx.inFlightReservations.containsKey(newRev)) {
            val fallbackEntry = ctx.inFlightReservations.entries.firstOrNull()
            if (fallbackEntry != null) {
                ctx.inFlightReservations.remove(fallbackEntry.key)
                ctx.inFlightReservations[newRev] = fallbackEntry.value
            }
        }

        val snap = ctx.state.snapshot()
        ctx.latestSnapshot = snap
        if(snap.getPage(ctx.viewport.activePageId ?: PageId("missing"))==null) {
            ctx.viewport=ctx.viewport.copy(activePageId=snap.pages.firstOrNull()?.id);publishedViewports[cmd.session]=ctx.viewport
        }
        governor.pinRenderSnapshot(cmd.session, snap)

        val saveStatus = ctx.computeSaveStatus()
        publishedSnapshots[cmd.session] = snap
        publishNotebook(ctx)
        publishedSaveStatuses[cmd.session] = saveStatus

        queues.emitMainEvent(
            AppEvent.SnapshotPublished(
                session = cmd.session,
                snapshot = snap,
                saveStatus = saveStatus
            )
        )
        queues.emitMainEvent(AppEvent.SaveStatusChanged(saveStatus))

        // Dispatch commit to Store
        dispatchCommitToStoreInternal(ctx, applied.writeSet, newRev, cmd.estimatedBytes, cmd.stagedAssets)
    }

    private fun executeUndoRedoInternal(session: SessionKey, isUndo: Boolean) {
        val ctx = activeSessions[session] ?: return
        val plan = if (isUndo) {
            ctx.history.undo(ctx.state.version)
        } else {
            ctx.history.redo(ctx.state.version)
        } ?: return

        val validated = validatePlan(ctx.state, plan, config.limits).getOrNull() ?: return
        val applied = applyPlan(ctx.state, validated).getOrNull() ?: return

        val newRev = applied.revision.value
        ctx.appliedRevision = newRev
        val reserve = queues.tryReservePersistAndAck(session, 512L).getOrNull()
        if (reserve != null) {
            ctx.inFlightReservations[newRev] = reserve
        }

        val snap = ctx.state.snapshot()
        ctx.latestSnapshot = snap
        governor.pinRenderSnapshot(session, snap)

        val saveStatus = ctx.computeSaveStatus()
        publishedSnapshots[session] = snap
        publishNotebook(ctx)
        publishedSaveStatuses[session] = saveStatus
        queues.emitMainEvent(AppEvent.SnapshotPublished(session, snap, saveStatus))

        dispatchCommitToStoreInternal(ctx, applied.writeSet, newRev, 512L, emptyList())
    }

    private fun dispatchCommitToStoreInternal(
        ctx: SessionContext,
        writeSet: WriteSet,
        newRev: Long,
        estimatedBytes: Long,
        stagedAssets: List<StagedAssetRef>
    ) {
        val commitReq = StorageRequest.Commit(
            revision = Revision(newRev),
            payloadBytes = estimatedBytes,
            transactionId = TransactionId("tx-${ctx.session.document.value}-$newRev"),
            expectedDurableBase = Revision(newRev - 1L),
            writeSet = writeSet,
            stagedAssets = stagedAssets
        )

        val customPort = ports.storagePort
        if (customPort != null) {
            customPort.trySubmit(commitReq)
        } else {
            val store = ctx.ownedStore ?: return
            storeExecutor.execute {
                val res = store.execute(commitReq)
                when (res) {
                    is Outcome.Success -> {
                        val committed = res.value as? StorageReply.Committed
                        if (committed != null) {
                            deliverDurableAck(committed.ack)
                        }
                    }
                    is Outcome.Failure -> {
                        engineExecutor.execute {
                            ctx.lastError = res.error.toString()
                            val errStatus = ctx.computeSaveStatus()
                            publishedSaveStatuses[ctx.session] = errStatus
                            queues.emitMainEvent(AppEvent.SaveStatusChanged(errStatus))
                            queues.emitMainEvent(
                                AppEvent.ErrorEvent(
                                    session = ctx.session,
                                    code = "STORE_COMMIT_ERROR",
                                    message = res.error.toString()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun flushPendingForSessionInternal(ctx: SessionContext): Int {
        var flushed = 0
        // Wait for storeExecutor to complete any queued store tasks
        if (!storeExecutor.isClosed()) {
            try {
                storeExecutor.submit { }.get(5, TimeUnit.SECONDS)
            } catch (_: Throwable) {
            }
        }
        while (true) {
            val ack = queues.pollNextDurableAck() ?: break
            processDurableAckInternal(ack)
            flushed += 1
        }
        return flushed
    }

    private fun processDurableAckInternal(ack: DurableAck) {
        val matchingContexts = activeSessions.values.filter { it.session.document == ack.document }
        for (ctx in matchingContexts) {
            val rev = ack.revision.value
            val reservation=ctx.inFlightReservations.remove(rev)
            reservation?.close()
            ctx.outOfOrderAckedRevisions.add(rev)

            // Advance durable watermark ONLY along a continuous prefix of revisions (1..k)
            while ((ctx.durableRevision + 1L) in ctx.outOfOrderAckedRevisions) {
                val nextDurable = ctx.durableRevision + 1L
                ctx.outOfOrderAckedRevisions.remove(nextDurable)
                ctx.durableRevision = nextDurable
            }
            if (ctx.inFlightReservations.size < config.persistQueueMaxEnvelopes) {
                ctx.isOverflowed = false
            }
            val saveStatus = ctx.computeSaveStatus()
            publishedSaveStatuses[ctx.session] = saveStatus
            queues.emitMainEvent(AppEvent.SaveStatusChanged(saveStatus))
        }
    }

    private fun updateViewportInternal(newState: ViewportState) {
        val ctx = activeSessions[newState.session] ?: return
        ctx.viewport = newState
        publishedViewports[newState.session] = newState
        publishNotebook(ctx)
        queues.emitMainEvent(AppEvent.ViewportChanged(newState))
    }

    private fun attachSurfaceInternal(cmd: AppCommand.AttachSurface) {
        val ctx = activeSessions[cmd.session] ?: return
        val updatedViewport = ctx.viewport.copy(
            widthPx = cmd.widthPx.coerceAtLeast(1),
            heightPx = cmd.heightPx.coerceAtLeast(1),
            isLandscape = cmd.widthPx > cmd.heightPx,
            surfaceEpoch = cmd.surfaceEpoch,
            resizeGeneration = cmd.resizeGeneration
        )
        ctx.viewport = updatedViewport
        publishedViewports[cmd.session] = updatedViewport
        publishNotebook(ctx)
        queues.emitMainEvent(
            AppEvent.SurfaceAttachedAck(
                session = cmd.session,
                surfaceEpoch = cmd.surfaceEpoch,
                resizeGeneration = cmd.resizeGeneration
            )
        )
        queues.emitMainEvent(AppEvent.ViewportChanged(updatedViewport))
    }

    private fun detachSurfaceInternal(cmd: AppCommand.DetachSurface) {
        // Render executor acknowledges surface release so no frame draws to a destroyed Surface
        if (!renderExecutor.isClosed()) {
            try {
                renderExecutor.submit { }.get(2, TimeUnit.SECONDS)
            } catch (_: Throwable) {
            }
        }
        queues.emitMainEvent(
            AppEvent.SurfaceTeardownAck(
                session = cmd.session,
                surfaceEpoch = cmd.surfaceEpoch
            )
        )
    }

    private fun sessionOf(command: AppCommand): SessionKey? {
        return when (command) {
            is AppCommand.OpenSession -> null
            is AppCommand.TrimMemory -> null
            is AppCommand.CloseSession -> command.session
            is AppCommand.SubmitEdit -> command.session
            is AppCommand.SubmitInputBatch -> command.batch.session
            is AppCommand.SubmitControl -> command.session
            is AppCommand.UpdateViewport -> command.state.session
            is AppCommand.AttachSurface -> command.session
            is AppCommand.DetachSurface -> command.session
            is AppCommand.BackgroundFlush -> command.session
            is AppCommand.Undo -> command.session
            is AppCommand.Redo -> command.session
            is AppCommand.DeliverJobReply -> command.session
        }
    }
}
