package inkspace.render

import android.graphics.Canvas
import inkspace.core.error.RenderError
import inkspace.core.error.SubmitError
import inkspace.core.id.SurfaceEpoch
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.SessionKey
import inkspace.core.outcome.Outcome
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.RenderJob
import inkspace.core.ports.RenderPort
import inkspace.core.ports.RequestId
import inkspace.core.ports.ResourcePort
import inkspace.core.ports.TextPort
import inkspace.core.render.FrameStats
import inkspace.core.render.RenderReply
import inkspace.core.render.RenderRequest
import inkspace.core.render.RenderTargetKind
import inkspace.core.runtime.MemoryCategory
import inkspace.core.runtime.MemoryPressureLevel
import inkspace.core.unit.CameraTransform
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Single unified renderer (`:tn-render`) implementing `RenderPort` and `Renderer.render(request)`.
 *
 * - `Renderer.render(request)` executes strictly on the single-thread `renderExecutor` (guarded by thread ID check).
 * - Screen `Draw` uses `screenCanvasReplay` on `renderExecutor`.
 * - Heavy `Snapshot` and `PdfOverlay` jobs can execute on `exportExecutor` using an isolated `exportCanvasReplay`
 *   instance so mutable `Paint`/`Path`/scratch arrays are never shared with screen rendering.
 */
class Renderer(
    private val geometryPort: GeometryPort,
    private val textPort: TextPort,
    private val resourcePort: ResourcePort,
    val maxDerivedCacheBytes: Long = MemoryCategory.DERIVED_PATHS_LAYOUT_SPATIAL.defaultCapBytes,
    val maxTileCacheBytes: Long = MemoryCategory.CPU_RASTER_TILES.defaultCapBytes,
    val maxControlledGraphicsBytes: Long = MemoryCategory.CONTROLLED_GRAPHICS_TARGETS.defaultCapBytes,
    val maxQueueDepth: Int = 16
) : RenderPort, AutoCloseable {

    private val renderThread = AtomicReference<Thread?>(null)
    private val exportThread = AtomicReference<Thread?>(null)
    private val closed = AtomicBoolean(false)

    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(
            {
                renderThread.set(Thread.currentThread())
                r.run()
            },
            "tn-render"
        ).apply { isDaemon = true }
    }

    private val exportExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(
            {
                exportThread.set(Thread.currentThread())
                r.run()
            },
            "tn-render-export"
        ).apply { isDaemon = true }
    }

    internal val derivedCache = DerivedArtifactCache(maxDerivedCacheBytes)
    internal val tileCache = RasterTileCache(maxTileCacheBytes)
    internal val surfaceManager = RenderSurfaceManager(maxControlledGraphicsBytes)

    private val screenCanvasReplay = CanvasReplay()
    private val exportCanvasReplay = CanvasReplay()

    private val requestSeq = AtomicLong(1L)
    private val queuedJobs = ArrayDeque<RenderJob>()

    init {
        // Warm up executor thread IDs
        renderExecutor.submit {}.get()
        exportExecutor.submit {}.get()
    }

    /**
     * Core API: `Renderer.render(request: RenderRequest): Outcome<FrameStats, RenderError>`.
     * Must be invoked on the `tn-render` executor thread.
     */
    fun render(
        request: RenderRequest,
        androidCanvas: Canvas? = null
    ): Outcome<FrameStats, RenderError> {
        val current = Thread.currentThread()
        val expected = renderThread.get()
        if (current !== expected) {
            return Outcome.Failure(
                RenderError.WrongThread(
                    expectedThread = expected?.name ?: "tn-render",
                    actualThread = current.name
                )
            )
        }
        return when (val replyOutcome = executeInternal(request, screenCanvasReplay, androidCanvas)) {
            is Outcome.Failure -> replyOutcome
            is Outcome.Success -> Outcome.Success(replyOutcome.value.stats)
        }
    }

    /**
     * Dispatches `Draw` onto the dedicated `renderExecutor` and `Snapshot` / `PdfOverlay` onto
     * the isolated `exportExecutor` (or `renderExecutor`), returning the typed `RenderReply`.
     */
    fun renderOnExecutor(
        request: RenderRequest,
        androidCanvas: Canvas? = null
    ): Outcome<RenderReply, RenderError> {
        if (closed.get()) {
            return Outcome.Failure(
                RenderError.InvalidSurface(
                    surfaceEpoch = request.scene.surfaceEpoch.value,
                    reason = "Renderer is closed"
                )
            )
        }
        return when (request) {
            is RenderRequest.Draw -> {
                renderExecutor.submit(
                    Callable {
                        executeInternal(request, screenCanvasReplay, androidCanvas)
                    }
                ).get()
            }
            is RenderRequest.Snapshot, is RenderRequest.PdfOverlay -> {
                exportExecutor.submit(
                    Callable {
                        executeInternal(request, exportCanvasReplay, androidCanvas)
                    }
                ).get()
            }
        }
    }

    private fun executeInternal(
        request: RenderRequest,
        replayEngine: CanvasReplay,
        androidCanvas: Canvas?
    ): Outcome<RenderReply, RenderError> {
        val startNs = System.nanoTime()
        val scene = request.scene

        val changeSet = scene.changeSet
        if (changeSet != null) {
            derivedCache.applyChangeSet(changeSet)
        }

        return when (request) {
            is RenderRequest.Draw -> {
                val admit = surfaceManager.validateSurfaceAndAdmitTarget(
                    surfaceEpoch = scene.surfaceEpoch,
                    widthPx = scene.viewportWidthPx,
                    heightPx = scene.viewportHeightPx,
                    needsOffscreenBuffer = request.capturePixels
                )
                if (admit is Outcome.Failure) return admit

                val prepared = ScenePreparer.prepare(scene)
                val replayRes = replayEngine.replay(
                    prepared = prepared,
                    camera = scene.camera,
                    targetWidthPx = scene.viewportWidthPx,
                    targetHeightPx = scene.viewportHeightPx,
                    computePixels = request.capturePixels,
                    derivedCache = derivedCache,
                    tileCache = tileCache,
                    geometryPort = geometryPort,
                    textPort = textPort,
                    resourcePort = resourcePort,
                    androidCanvas = androidCanvas
                )
                val (counters, pixels) = when (replayRes) {
                    is Outcome.Failure -> return replayRes
                    is Outcome.Success -> replayRes.value
                }

                val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1L)
                val stats = buildFrameStats(
                    scene = scene,
                    targetKind = RenderTargetKind.SURFACE,
                    prepared = prepared,
                    counters = counters,
                    elapsedNs = elapsedNs
                )
                Outcome.Success(
                    RenderReply.FrameDrawn(
                        stats = stats,
                        widthPx = scene.viewportWidthPx,
                        heightPx = scene.viewportHeightPx,
                        capturedArgbPixels = pixels
                    )
                )
            }

            is RenderRequest.Snapshot -> {
                val scale = request.scale.coerceAtLeast(0.01)
                val wPx = (request.regionDoc.width * scale).roundToInt().coerceAtLeast(1)
                val hPx = (request.regionDoc.height * scale).roundToInt().coerceAtLeast(1)
                val admit = surfaceManager.validateSurfaceAndAdmitTarget(
                    surfaceEpoch = scene.surfaceEpoch,
                    widthPx = wPx,
                    heightPx = hPx,
                    needsOffscreenBuffer = true
                )
                if (admit is Outcome.Failure) return admit

                val snapCamera = CameraTransform(
                    scale = scale,
                    translateX = -request.regionDoc.left * scale,
                    translateY = -request.regionDoc.top * scale
                )
                val prepared = ScenePreparer.prepare(
                    scene = scene.copy(viewportWidthPx = wPx, viewportHeightPx = hPx, camera = snapCamera),
                    overrideViewportDocRect = request.regionDoc,
                    overrideCamera = snapCamera
                )
                val replayRes = replayEngine.replay(
                    prepared = prepared,
                    camera = snapCamera,
                    targetWidthPx = wPx,
                    targetHeightPx = hPx,
                    computePixels = true,
                    derivedCache = derivedCache,
                    tileCache = tileCache,
                    geometryPort = geometryPort,
                    textPort = textPort,
                    resourcePort = resourcePort,
                    androidCanvas = androidCanvas
                )
                val (counters, pixels) = when (replayRes) {
                    is Outcome.Failure -> return replayRes
                    is Outcome.Success -> replayRes.value
                }
                val outPixels = pixels ?: IntArray(wPx * hPx)
                val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1L)
                val stats = buildFrameStats(
                    scene = scene,
                    targetKind = RenderTargetKind.SNAPSHOT_IMAGE,
                    prepared = prepared,
                    counters = counters,
                    elapsedNs = elapsedNs
                )
                Outcome.Success(
                    RenderReply.SnapshotRendered(
                        stats = stats,
                        widthPx = wPx,
                        heightPx = hPx,
                        argbPixels = outPixels
                    )
                )
            }

            is RenderRequest.PdfOverlay -> {
                val wPt = request.pageBoundsDoc.width.roundToInt().coerceAtLeast(1)
                val hPt = request.pageBoundsDoc.height.roundToInt().coerceAtLeast(1)
                val admit = surfaceManager.validateSurfaceAndAdmitTarget(
                    surfaceEpoch = scene.surfaceEpoch,
                    widthPx = wPt,
                    heightPx = hPt,
                    needsOffscreenBuffer = androidCanvas == null
                )
                if (admit is Outcome.Failure) return admit

                val pdfCamera = CameraTransform(
                    scale = 1.0,
                    translateX = -request.pageBoundsDoc.left,
                    translateY = -request.pageBoundsDoc.top
                )
                val prepared = ScenePreparer.prepare(
                    scene = scene.copy(viewportWidthPx = wPt, viewportHeightPx = hPt, camera = pdfCamera),
                    overrideViewportDocRect = request.pageBoundsDoc,
                    overrideCamera = pdfCamera,
                    objectIds = request.objectIds
                )
                val pdfCommands = StringBuilder(256)
                val replayRes = replayEngine.replay(
                    prepared = prepared,
                    camera = pdfCamera,
                    targetWidthPx = wPt,
                    targetHeightPx = hPt,
                    computePixels = androidCanvas == null,
                    derivedCache = derivedCache,
                    tileCache = tileCache,
                    geometryPort = geometryPort,
                    textPort = textPort,
                    resourcePort = resourcePort,
                    androidCanvas = androidCanvas,
                    pdfVectorCommandsOut = pdfCommands,
                    includePaper = request.includePaper
                )
                val (counters, pixels) = when (replayRes) {
                    is Outcome.Failure -> return replayRes
                    is Outcome.Success -> replayRes.value
                }
                val previewPixels = pixels ?: IntArray(0)
                val pdfBytes = CanvasReplay.buildPdfOverlayBytes(wPt, hPt, pdfCommands.toString())
                val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1L)
                val stats = buildFrameStats(
                    scene = scene,
                    targetKind = RenderTargetKind.PDF_OVERLAY,
                    prepared = prepared,
                    counters = counters,
                    elapsedNs = elapsedNs
                )
                Outcome.Success(
                    RenderReply.PdfOverlayRendered(
                        stats = stats,
                        pageId = request.pageId,
                        widthPt = wPt,
                        heightPt = hPt,
                        pdfBytes = pdfBytes,
                        rasterPreviewArgb = previewPixels
                    )
                )
            }
        }
    }

    private fun buildFrameStats(
        scene: inkspace.core.render.Scene,
        targetKind: RenderTargetKind,
        prepared: PreparedScene,
        counters: ReplayCounters,
        elapsedNs: Long
    ): FrameStats {
        return FrameStats(
            revision = scene.snapshot.version.revision,
            surfaceEpoch = scene.surfaceEpoch,
            resizeGeneration = scene.resizeGeneration,
            targetKind = targetKind,
            totalSceneObjects = prepared.totalSceneObjects,
            visiblePagesCount = prepared.visiblePages.size,
            visibleObjectsCount = prepared.visibleObjectsCount,
            culledObjectsCount = prepared.culledObjectsCount,
            drawnStrokesCount = counters.drawnStrokes,
            drawnShapesCount = counters.drawnShapes,
            drawnTextsCount = counters.drawnTexts,
            drawnTilesCount = counters.drawnTiles,
            draftStrokesCount = counters.drawnDrafts,
            ephemeralOverlaysCount = scene.frozenEphemeralOverlays.size,
            geometryCacheHits = counters.geometryHits,
            geometryRebuildsCount = counters.geometryRebuilds,
            textCacheHits = counters.textHits,
            textRebuildsCount = counters.textRebuilds,
            tileCacheHits = counters.tileHits,
            scaleBucket = prepared.scaleBucket,
            frameDurationNanos = elapsedNs,
            derivedCacheBytes = derivedCache.currentUsedBytes(),
            tileCacheBytes = tileCache.currentUsedBytes(),
            controlledGraphicsBytes = surfaceManager.controlledGraphicsBytes(),
            sdkReportedGraphicsBytes = surfaceManager.sdkReportedGraphicsBytes(),
            pinnedSnapshotsCount = surfaceManager.pinnedSnapshotsCount()
        )
    }

    @Synchronized
    override fun trySubmit(job: RenderJob): Outcome<RequestId, SubmitError> {
        if (closed.get()) {
            return Outcome.Failure(SubmitError.ShutdownInProgress("Renderer is closed"))
        }
        if (queuedJobs.size >= maxQueueDepth) {
            return Outcome.Failure(SubmitError.QueueOverflow(queuedJobs.size, maxQueueDepth))
        }
        queuedJobs.addLast(job)
        return Outcome.Success(RequestId(requestSeq.getAndIncrement()))
    }

    @Synchronized
    fun pollNextJob(): RenderJob? = if (queuedJobs.isEmpty()) null else queuedJobs.removeFirst()

    fun pinSnapshot(session: SessionKey, snapshot: DocumentSnapshot): Boolean =
        surfaceManager.pinSnapshot(session, snapshot)

    fun unpinSessionSnapshots(session: SessionKey) =
        surfaceManager.unpinSessionSnapshots(session)

    fun pinnedSnapshotsCount(session: SessionKey? = null): Int =
        surfaceManager.pinnedSnapshotsCount(session)

    fun releaseSurface(epoch: SurfaceEpoch): Boolean =
        surfaceManager.releaseSurface(epoch)

    fun isSurfaceReleased(epoch: SurfaceEpoch): Boolean =
        surfaceManager.isSurfaceReleased(epoch)

    fun releaseTransientTiles() { tileCache.clear() }

    fun trimMemory(level: MemoryPressureLevel) {
        when (level) {
            MemoryPressureLevel.CRITICAL -> {
                derivedCache.clear()
                tileCache.clear()
            }
            MemoryPressureLevel.MODERATE -> {
                derivedCache.trimToBytes(maxDerivedCacheBytes / 2)
                tileCache.clear()
            }
            MemoryPressureLevel.LOW -> {
                derivedCache.trimToBytes((maxDerivedCacheBytes * 3) / 4)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            renderExecutor.shutdownNow()
            exportExecutor.shutdownNow()
            derivedCache.clear()
            tileCache.clear()
            surfaceManager.clear()
        }
    }
}
