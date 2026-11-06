package inkspace.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.Surface
import inkspace.content.*
import inkspace.core.error.FeatureError
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.*
import inkspace.core.ports.RequestId
import inkspace.core.render.*
import inkspace.core.runtime.AppCommand
import inkspace.core.unit.*
import inkspace.core.workspace.NotebookThumbnail
import inkspace.render.Renderer
import inkspace.text.*
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One bounded Android work owner coalesces frames; actor/Main never stage, decode or wait for rendering. */
class AndroidNotebookFeatures(private val runtime: Runtime, private val context: Context) : AutoCloseable {
    private val worker = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(8)) { task -> Thread(task,"tn-android-features").apply { isDaemon=true } }
    private val bindings = LinkedHashMap<SessionKey,Surface>()
    private val pending = LinkedHashSet<SessionKey>()
    private var rendererInstance: Renderer? = null
    private var textPortInstance: AndroidTextPort? = null
    private var derivedLease: MemoryLease? = null
    @Volatile private var activeAssets: StreamingAssetStager? = null
    @Volatile private var activeSession: SessionKey? = null
    private var animateImages = true
    private var activeGif: Triple<SessionKey,AssetId,LocalGif>? = null
    private var gifLease: MemoryLease? = null
    private data class GifClock(val elapsedMs:Long,val lastVisibleNs:Long)
    private val gifClocks=LinkedHashMap<Pair<SessionKey,AssetId>,GifClock>(16,.75f,true)
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private val draftTexts = HashMap<SessionKey,Pair<ObjectId,TextPayload>>()
    private val draftBounds = HashMap<SessionKey,DocRect>()
    private val reveals = HashMap<SessionKey,TapeRevealState>()
    private val sequence = AtomicLong(1_000_000)
    private val pdf = AndroidPdfWorkspace(context.applicationContext,runtime)
    private var scheduled = false
    @Volatile private var closed = false

    private fun documentDirectory(session: SessionKey): File = File(runtime.config.storageRootPath,"documents/${session.document.value}")
    fun bind(session: SessionKey,surface: Surface) {
        synchronized(bindings) {
            while(bindings.size >= runtime.config.maxWarmSessions && session !in bindings) bindings.remove(bindings.keys.first())
            bindings[session]=surface
        }
        refresh(session)
    }
    fun unbind(session: SessionKey) {
        synchronized(bindings) { bindings.remove(session) }
        synchronized(pending) { pending.remove(session) }
        synchronized(draftTexts) { draftTexts.remove(session); draftBounds.remove(session) }
        synchronized(reveals) { reveals.remove(session) }
    }
    fun refresh(session: SessionKey) {
        if(closed) return
        synchronized(pending) {
            if(pending.size >= runtime.config.maxWarmSessions && session !in pending) pending.remove(pending.first())
            pending.add(session)
            if(scheduled) return
            scheduled=true
        }
        try { worker.execute(::drainFrames) } catch (_: java.util.concurrent.RejectedExecutionException) { synchronized(pending) { scheduled=false } }
    }
    private fun drainFrames() {
        while(!closed) {
            val session = synchronized(pending) {
                if(pending.isEmpty()) { scheduled=false; return }
                pending.first().also { pending.remove(it) }
            }
            try { draw(session) } catch(error: Exception) {
                runtime.reportFeatureError(session,FeatureError.InvalidParameters(error.message ?: "Frame failed"))
            }
        }
    }
    @Synchronized private fun renderer(session: SessionKey): Renderer {
        check(!closed) { "Android workspace closed" }
        activeAssets=StreamingAssetStager(documentDirectory(session))
        activeSession=session
        rendererInstance?.let { return it }
        val lease=runtime.governor.tryReserve(inkspace.core.runtime.MemoryCategory.DERIVED_PATHS_LAYOUT_SPATIAL,
            22L*1024*1024,"android-font-layout-geometry").getOrNull() ?: throw IllegalStateException("Layout/font memory cap")
        derivedLease=lease
        val assetPort=object : inkspace.core.content.AssetStagingPort {
            override fun stage(source: java.io.InputStream,maxBytes: Long,suffix: String)=requireNotNull(activeAssets).stage(source,maxBytes,suffix)
            override fun open(asset: AssetId)=requireNotNull(activeAssets).open(asset)
        }
        val text=AndroidTextPort(fonts=FontRepository(File(context.cacheDir,"notebook-fonts"),assetPort,maxFiles=1,maxBytes=8L*1024*1024))
        textPortInstance=text
        val images=object : inkspace.core.ports.ResourcePort {
            override fun read(request: inkspace.core.ports.ResourceRequest): Outcome<inkspace.core.ports.ResourceLease,inkspace.core.error.ResourceError> {
                val assets=requireNotNull(activeAssets)
                val renderingSession=requireNotNull(activeSession)
                return try {
                    val file=assets.localFile(request.asset)
                    val signature=file.inputStream().use { input -> ByteArray(6).also { input.read(it) } }
                    if(signature.toString(Charsets.US_ASCII).startsWith("GIF")) {
                        if(activeGif?.first!=renderingSession || activeGif?.second!=request.asset) {
                            activeGif?.third?.close();activeGif=null;gifLease?.close();gifLease=null
                            val descriptor=file.inputStream().use { GifPreflight.inspect(it,maxDecodedBytes=8L*1024*1024) }
                            val reserved=runtime.governor.tryReserve(inkspace.core.runtime.MemoryCategory.CPU_RASTER_TILES,descriptor.decodedBytes,"active-gif").getOrNull()
                                ?: return Outcome.Failure(inkspace.core.error.ResourceError.ResourceExhausted("GIF budget exceeded"))
                            try { activeGif=Triple(renderingSession,request.asset,LocalGif(file,request.posterFrame,8L*1024*1024));gifLease=reserved }
                            catch(error:Exception) { reserved.close();throw error }
                        }
                        val gif=activeGif!!.third
                        val now=System.nanoTime()
                        if(animateImages) {
                            val key=renderingSession to request.asset
                            val clock=gifClocks[key]
                            val delta=if(clock!=null && now-clock.lastVisibleNs<=500_000_000L) (now-clock.lastVisibleNs).coerceAtLeast(0)/1_000_000 else 0
                            val elapsed=((clock?.elapsedMs ?: 0)+delta)%gif.descriptor.durationMs
                            gif.seekElapsed(elapsed)
                            if(gifClocks.size>=128 && key !in gifClocks) gifClocks.remove(gifClocks.keys.first())
                            gifClocks[key]=GifClock(elapsed,now)
                        }
                        val bitmap=Bitmap.createBitmap(gif.descriptor.width,gif.descriptor.height,Bitmap.Config.ARGB_8888)
                        val tile=try {
                            gif.draw(android.graphics.Canvas(bitmap),staticExport=!animateImages)
                            val pixels=IntArray(bitmap.width*bitmap.height)
                            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                            TileArtifact("gif-${request.asset.value}-$now",request.asset,docBounds=DocRect(0.0,0.0,bitmap.width.toDouble(),bitmap.height.toDouble()),widthPx=bitmap.width,heightPx=bitmap.height,argbPixels=pixels)
                        } finally {bitmap.recycle()}
                        Outcome.Success(inkspace.core.ports.ResourceLease(request.asset,tile.byteSize,true,imageTile=tile))
                    } else AndroidImageResourcePort(AndroidImageDecoder(assets)).read(request.copy(byteBudget=minOf(request.byteBudget,8L*1024*1024)))
                } catch(error:Exception) { Outcome.Failure(inkspace.core.error.ResourceError.CorruptedResource(request.asset.value,error.message ?: "Image decode failed")) }
            }
        }
        return Renderer(runtime.defaultGeometryPort,text,images,maxDerivedCacheBytes=12L*1024*1024,maxTileCacheBytes=16L*1024*1024)
            .also { rendererInstance=it }
    }
    private fun draw(session: SessionKey) {
        val surface=synchronized(bindings) { bindings[session] } ?: return
        if(!surface.isValid) return
        var scene=runtime.sceneForRendering(session) ?: return
        val viewport=runtime.latestViewport(session) ?: return
        val pdfTiles=pdf.tiles(session,scene.snapshot,viewport)
        if(pdfTiles.isNotEmpty()) scene=scene.copy(tiles=scene.frozenTiles+pdfTiles)
        val reveal=synchronized(reveals) { reveals[session] }
        if(reveal!=null) {
            val ids=scene.snapshot.directory.allResidentObjects().filter { it.payload is TapePayload && reveal.isRevealed(it.id) }.map { it.id }.toSet()
            scene=scene.copy(revealedTapeIds=ids)
        }
        val draft=synchronized(draftTexts) { draftTexts[session] }
        if(draft!=null) {
            val existing=scene.snapshot.getObject(draft.first)
            val page=scene.snapshot.getPage(viewport.activePageId ?: scene.snapshot.pages.first().id)
            val layer=scene.snapshot.layers.firstOrNull { it.pageId==page?.id && it.isVisible && !it.isLocked }
            val bounds=synchronized(draftTexts) { draftBounds[session] }
            val objectSnapshot=existing ?: if(page!=null && layer!=null && bounds!=null)
                SceneObject(draft.first,page.spaceId,layer.id,bounds,draft.second.copy(containerBounds=bounds)) else null
            if(objectSnapshot!=null) {
                val state=DocumentState.fromSnapshot(scene.snapshot)
                val payload=when(val old=objectSnapshot.payload) { is ShapePayload -> old.copy(label=draft.second); is StickyPayload -> old.copy(richText=draft.second,content=draft.second.text); else -> draft.second }
                state.putResidentObjectDirect(objectSnapshot.copy(payload=payload,payloadDigest=inkspace.core.codec.CoreCodec.computePayloadDigest(payload)))
                scene=scene.copy(snapshot=state.snapshot())
            }
        }
        val factor=kotlin.math.min(1.0,kotlin.math.sqrt(4_000_000.0/(scene.viewportWidthPx.toLong()*scene.viewportHeightPx).coerceAtLeast(1)))
        if(factor<1) scene=scene.copy(viewportWidthPx=(scene.viewportWidthPx*factor).toInt().coerceAtLeast(1),viewportHeightPx=(scene.viewportHeightPx*factor).toInt().coerceAtLeast(1),
            camera=scene.camera.copy(scale=scene.camera.scale*factor,translateX=scene.camera.translateX*factor,translateY=scene.camera.translateY*factor))
        val cpu=runtime.governor.tryReserve(inkspace.core.runtime.MemoryCategory.CPU_RASTER_TILES,8L*1024*1024,"frame-raster-work").getOrNull() ?: return
        val targetGraphicsBytes = scene.viewportWidthPx.toLong() * scene.viewportHeightPx * 8L
        val graphics=runtime.governor.tryReserve(inkspace.core.runtime.MemoryCategory.CONTROLLED_GRAPHICS_TARGETS,
            targetGraphicsBytes,"android-frame-targets").getOrNull()
        if(graphics==null) {
            android.util.Log.e("TruffnotesDraw", "Failed to reserve frame graphics budget: $targetGraphicsBytes bytes")
            cpu.close();return
        }
        try {
        animateImages=true
        val result=renderer(session).renderOnExecutor(RenderRequest.Draw(scene,capturePixels=true))
        if(result is Outcome.Failure) {
            android.util.Log.e("TruffnotesDraw", "Render failed: ${result.error}")
            runtime.reportFeatureError(session, FeatureError.InvalidParameters(result.error.toString()))
            return
        }
        val frame=result.getOrNull() as? RenderReply.FrameDrawn ?: return
        val pixels=frame.copyCapturedPixels() ?: return
        val bitmap=Bitmap.createBitmap(pixels,frame.widthPx,frame.heightPx,Bitmap.Config.ARGB_8888)
        try {
            if(synchronized(bindings) { bindings[session] !== surface } || !surface.isValid) return
            val canvas=surface.lockCanvas(null) ?: return
            try { canvas.drawBitmap(bitmap,null,Rect(0,0,canvas.width,canvas.height),null) } finally { surface.unlockCanvasAndPost(canvas) }
        } finally { bitmap.recycle() }
        val gif=activeGif
        if(gif?.first==session && scene.snapshot.directory.allResidentObjects().any { (it.payload as? ImagePayload)?.asset==gif.second && it.bounds.intersects(inkspace.render.CameraRelativeProjector.visibleWorldRect(scene.camera,scene.viewportWidthPx,scene.viewportHeightPx)) })
            mainHandler.postDelayed({ if(!closed && synchronized(bindings) { bindings.containsKey(session) }) refresh(session) },80)
        } finally { rendererInstance?.releaseTransientTiles();graphics.close();cpu.close() }
    }

    fun createTextEditorView(viewContext: Context,session: SessionKey,id: ObjectId?): TextInputView {
        check(Looper.myLooper()==Looper.getMainLooper())
        val snapshot=runtime.latestSnapshot(session) ?: throw IllegalStateException("Session closed")
        val viewport=runtime.latestViewport(session) ?: throw IllegalStateException("No viewport")
        require(!viewport.isReadOnly) { "Notebook is read-only" }
        val target=id ?: viewport.selectedObjectIds.firstOrNull { TextPlanner.textOf(snapshot.getObjectPayload(it))!=null }
        val obj=target?.let(snapshot::getObject)
        val text=obj?.payload?.let(TextPlanner::textOf) ?: TextPayload("")
        val objectId=obj?.id ?: ObjectId("text-${sequence.getAndIncrement()}")
        val origin=viewport.camera.viewToDoc(ViewPoint(32.0,32.0))
        val bounds=obj?.bounds ?: DocRect(origin.x,origin.y,origin.x+300,origin.y+120)
        synchronized(draftTexts) { draftBounds[session]=bounds }
        val editor=TextInputView(viewContext)
        val editSession=TextEditSession(objectId,text,sequence.getAndIncrement())
        editor.bind(editSession)
        fun prepareEditor(payload:TextPayload) {
            try {worker.execute {
                try {
                    renderer(session)
                    val container=payload.containerBounds ?: bounds
                    val plan=requireNotNull(textPortInstance).paintPlan(payload,container.width)
                    mainHandler.post { if(!closed && editSession.focused && editSession.payload.text==payload.text) editor.paintPlan=plan }
                    if(payload.pageBound && plan.layout.height>container.height)
                        runtime.reportFeatureError(session,FeatureError.InvalidParameters("Text overflows page; all text is retained"))
                } catch(error:Exception) {runtime.reportFeatureError(session,FeatureError.InvalidParameters(error.message ?: "Text layout failed"))}
            }} catch(_:java.util.concurrent.RejectedExecutionException) { }
        }
        editor.onDraft={ payload -> synchronized(draftTexts) { draftTexts[session]=objectId to payload };prepareEditor(payload);refresh(session) }
        prepareEditor(text)
        editor.onCommit={ payload ->
            synchronized(draftTexts) { draftTexts[session]=objectId to payload }
            val action=if(runtime.latestSnapshot(session)?.getObject(objectId)==null) TextAction.Create(objectId,payload,bounds) else TextAction.Replace(objectId,payload)
            try { worker.execute {
                val result=runtime.submitTextAction(session,action,GestureId("text-input-${sequence.getAndIncrement()}"))
                result.errorOrNull()?.let { runtime.reportFeatureError(session,it) }
                refresh(session)
            } } catch (_: java.util.concurrent.RejectedExecutionException) {
                runtime.reportFeatureError(session,FeatureError.InvalidParameters("Text commit queue is full"))
            }
        }
        val local=text.containerBounds ?: obj?.transform?.invert()?.transformRect(bounds) ?: bounds
        val transform=obj?.transform ?: AffineTransform2D.IDENTITY
        val topLeft=transform.transformPoint(DocPoint(local.left,local.top))
        val scale=viewport.camera.scale
        editor.translationX=(topLeft.x*scale+viewport.camera.translateX).toFloat()
        editor.translationY=(topLeft.y*scale+viewport.camera.translateY).toFloat()
        editor.pivotX=0f;editor.pivotY=0f
        editor.animationMatrix=android.graphics.Matrix().apply {setValues(floatArrayOf(
            (transform.m00*scale).toFloat(),(transform.m01*scale).toFloat(),0f,
            (transform.m10*scale).toFloat(),(transform.m11*scale).toFloat(),0f,0f,0f,1f))}
        editor.layoutParams=android.view.ViewGroup.LayoutParams(local.width.toInt().coerceAtLeast(1),local.height.toInt().coerceAtLeast(48))
        return editor
    }
    fun toggleTapeReveal(session: SessionKey,id: ObjectId) {
        synchronized(reveals) { reveals.getOrPut(session) { TapeRevealState() }.toggle(id) }
        refresh(session)
    }
    fun importResource(session: SessionKey,uri: Uri,mime: String?,asDocument: Boolean=false): Outcome<RequestId,FeatureError> {
        if(closed) return failure("Android workspace closed")
        if(runtime.latestViewport(session)?.isReadOnly!=false) return failure("Notebook is read-only or closed")
        val accepted=RequestId(sequence.getAndIncrement())
        try { worker.execute {
            try {
                if(mime=="application/pdf") {
                    val result=pdf.importPdf(session,uri,asDocument)
                    result.errorOrNull()?.let { runtime.reportFeatureError(session,it) }
                } else {
                    val staging=StreamingAssetStager(documentDirectory(session))
                    val importer=AndroidContentImporter(context.contentResolver,staging,AndroidImageDecoder(staging))
                    when(val imported=importer.importUri(uri)) {
                        is Outcome.Failure -> runtime.reportFeatureError(session,FeatureError.InvalidParameters(imported.error.reason))
                        is Outcome.Success -> {
                            val snapshot=runtime.latestSnapshot(session) ?: return@execute
                            val viewport=runtime.latestViewport(session) ?: return@execute
                            val page=snapshot.getPage(viewport.activePageId ?: snapshot.pages.first().id) ?: return@execute
                            val layer=snapshot.layers.firstOrNull { it.pageId==page.id && !it.isLocked && it.isVisible } ?: return@execute
                            val point=viewport.camera.viewToDoc(ViewPoint(32.0,32.0))
                            val resource=imported.value
                            val width=300.0
                            val height=width*resource.height/resource.width
                            val action=ContentAction.InsertImage(ObjectId("image-${sequence.getAndIncrement()}"),resource.asset.assetId,resource.width,resource.height,
                                DocRect(point.x,point.y,point.x+width,point.y+height),resource.exifOrientation)
                            val ctx=PlanContext(activeSpaceId=page.spaceId,activePageId=page.id,activeLayerId=layer.id,gestureId=GestureId("import-${accepted.value}"))
                            when(val plan=ContentPlanner.plan(snapshot,action,ctx)) {
                                is Outcome.Failure -> runtime.reportFeatureError(session,plan.error)
                                is Outcome.Success -> runtime.trySend(AppCommand.SubmitEdit(session,plan.value,stagedAssets=listOf(resource.asset)))
                            }
                        }
                    }
                }
                refresh(session)
            } catch(error: Exception) { runtime.reportFeatureError(session,FeatureError.InvalidParameters(error.message ?: "Import failed")) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { return failure("Import queue is full") }
        return Outcome.Success(accepted)
    }
    fun thumbnail(documentId: String,maxEdge: Int): Outcome<NotebookThumbnail,FeatureError> {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Thumbnail work must not run on Main" }
        if(maxEdge !in 16..512) return failure("Invalid thumbnail size")
        return try {
            // Serialize thumbnail access with render ownership; caller is already a library worker.
            worker.submit<Outcome<NotebookThumbnail,FeatureError>> {
                val snapshot=runtime.snapshotForThumbnail(documentId) ?: return@submit failure("Notebook missing")
                val page=snapshot.pages.firstOrNull() ?: return@submit failure("Notebook has no pages")
                val scale=maxEdge.toDouble()/kotlin.math.max(page.widthPt,page.heightPt)
                val scene=Scene(snapshot,activeSpaceId=page.spaceId,viewportWidthPx=(page.widthPt*scale).toInt().coerceAtLeast(1),
                    viewportHeightPx=(page.heightPt*scale).toInt().coerceAtLeast(1),camera=CameraTransform(scale=scale))
                val session=SessionKey(snapshot.version.document,SessionEpoch(1))
                val cpu=runtime.governor.tryReserve(inkspace.core.runtime.MemoryCategory.CPU_RASTER_TILES,8L*1024*1024,"thumbnail-work").getOrNull() ?: return@submit failure("Thumbnail memory cap")
                val graphics=runtime.governor.tryReserve(inkspace.core.runtime.MemoryCategory.CONTROLLED_GRAPHICS_TARGETS,
                    scene.viewportWidthPx.toLong()*scene.viewportHeightPx*8L,"thumbnail-target").getOrNull()
                if(graphics==null) {cpu.close();return@submit failure("Thumbnail graphics cap")}
                try {
                    animateImages=false
                    val viewport=inkspace.core.runtime.ViewportState(session,camera=scene.camera,widthPx=scene.viewportWidthPx,heightPx=scene.viewportHeightPx,activePageId=page.id)
                    val effectiveScene=scene.copy(tiles=pdf.tiles(session,snapshot,viewport))
                    val frame=renderer(session).renderOnExecutor(RenderRequest.Draw(effectiveScene,true)).getOrNull() as? RenderReply.FrameDrawn
                        ?: return@submit failure("Thumbnail render failed")
                    Outcome.Success(NotebookThumbnail(frame.widthPx,frame.heightPx,frame.copyCapturedPixels()!!))
                } finally {rendererInstance?.releaseTransientTiles();graphics.close();cpu.close()}
            }.get()
        } catch(error: Exception) { failure(error.message ?: "Thumbnail failed") }
    }
    @Synchronized override fun close() {
        closed=true
        synchronized(bindings) { bindings.clear() }
        worker.shutdownNow()
        rendererInstance?.close();rendererInstance=null
        textPortInstance?.clear();textPortInstance=null
        derivedLease?.close();derivedLease=null
        activeGif?.third?.close();activeGif=null;gifLease?.close();gifLease=null
        mainHandler.removeCallbacksAndMessages(null)
        pdf.close()
    }
    private fun <T> failure(reason: String): Outcome<T,FeatureError> = Outcome.Failure(FeatureError.InvalidParameters(reason))
}
