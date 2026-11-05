package inkspace.runtime

import inkspace.core.codec.WriteSet
import inkspace.core.error.StartError
import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.TransactionId
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.DocumentState
import inkspace.core.model.SessionKey
import inkspace.core.mutation.History
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.outcome.Outcome
import inkspace.core.ports.DurableAck
import inkspace.core.ports.RequestId
import inkspace.core.ports.StagedAssetRef
import inkspace.core.ports.StorageReply
import inkspace.core.ports.StorageRequest
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
        val pendingCommandQueue = ArrayDeque<AppCommand>()
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

    override fun closeAsync(): Outcome<RequestId, SubmitError> {
        if (!shuttingDown.compareAndSet(false, true)) {
            return Outcome.Failure(SubmitError.ShutdownInProgress("closeAsync already in progress"))
        }
        val reqId = RequestId(requestIdGen.getAndIncrement())
        pauseEngineLoop.set(false)
        engineExecutor.execute {
            drainAllQueuesInternal()
            var flushedCommits = 0
            for (ctx in activeSessions.values) {
                flushedCommits += flushPendingForSessionInternal(ctx)
                ctx.ownedStore?.let { store ->
                    store.execute(StorageRequest.Close(ctx.session.document))
                }
                ctx.closed = true
            }
            completedPendingAtShutdown.set(flushedCommits.toLong())
            queues.emitMainEvent(AppEvent.Closed(flushedCommits))
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

        // 4. Round-robin up to 2 commands per session so heavy operations on one document never starve another
        val sessionsSnapshot = synchronized(this) { roundRobinOrder.toList() }
        for (sessionKey in sessionsSnapshot) {
            for (step in 0 until 2) {
                val cmd = synchronized(this) {
                    val ctx = activeSessions[sessionKey]
                    if (ctx == null || ctx.pendingCommandQueue.isEmpty()) null else ctx.pendingCommandQueue.removeFirst()
                } ?: break
                processCommandInternal(cmd, traceLog)
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
        governor.pinRenderSnapshot(cmd.session, snap)

        val saveStatus = ctx.computeSaveStatus()
        publishedSnapshots[cmd.session] = snap
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
            ctx.inFlightReservations.remove(rev)?.close()
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
