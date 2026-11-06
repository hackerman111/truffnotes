package inkspace.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import inkspace.content.StreamingAssetStager
import inkspace.core.error.FeatureError
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.PdfObjectPayload
import inkspace.core.pdf.*
import inkspace.core.ports.PdfRequest
import inkspace.core.ports.RequestId
import inkspace.core.render.TileArtifact
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.MemoryCategory
import inkspace.core.runtime.ViewportState
import inkspace.core.unit.*
import inkspace.pdf.PdfImportAction
import inkspace.pdf.PdfImportPlanner
import inkspace.pdf.PdfPageTransform
import inkspace.pdf.android.AndroidPdfPort
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.sqrt

/** Blocking operations belong to the runtime's bounded worker, never to Main or the engine actor. */
class AndroidPdfWorkspace(private val context: Context, private val runtime: Runtime) : AutoCloseable {
    private data class Cached(val tile: TileArtifact, val lease: MemoryLease)
    private val pendingLock = Any()
    private val pending = mutableMapOf<RequestId, CompletableFuture<PdfReply>>()
    private val sourceFiles = ConcurrentHashMap<AssetId, File>()
    private val handles = LinkedHashMap<Pair<DocumentId, AssetId>, PdfHandle>(16, 0.75f, true)
    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private val cacheLock = Any()
    private var cacheBytes = 0L
    private val generation = AtomicLong(1)
    private var seenGeneration = 1L
    @Volatile private var closed = false
    private val port = AndroidPdfPort(context, { asset ->
        ParcelFileDescriptor.open(requireNotNull(sourceFiles[asset]), ParcelFileDescriptor.MODE_READ_ONLY)
    }, { id, reply -> synchronized(pendingLock) { pending.remove(id)?.complete(reply) }; Unit }, {
        generation.incrementAndGet()
    })

    private fun workerOnly() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PDF work must run off Main" }
        check(!closed) { "PDF workspace closed" }
        if (seenGeneration != generation.get()) {
            handles.clear()
            clearCache()
            seenGeneration = generation.get()
        }
    }

    private fun await(request: PdfRequest): PdfReply {
        workerOnly()
        val future = CompletableFuture<PdfReply>()
        val id = synchronized(pendingLock) {
            val submitted = port.trySubmit(request)
            val id = submitted.getOrNull() ?: error(submitted.toString())
            pending[id] = future
            id
        }
        val reply = try { future.get(15, TimeUnit.SECONDS) } catch (failure: Exception) {
            synchronized(pendingLock) { pending.remove(id) }
            port.cancel(id)
            throw failure
        }
        if (reply is PdfReply.Failed) error(reply.reason)
        return reply
    }

    private fun stager(session: SessionKey): StreamingAssetStager {
        val root = File(runtime.config.storageRootPath, "documents").canonicalFile
        val document = File(root, session.document.value).canonicalFile
        require(document.toPath().startsWith(root.toPath()) && document != root)
        return StreamingAssetStager(document)
    }

    private fun handle(session: SessionKey, asset: AssetId, password: String? = null): PdfHandle {
        val key = session.document to asset
        handles[key]?.let { return it }
        if (handles.size >= 12) {
            val oldest = handles.entries.first()
            await(PdfRequest.Close(oldest.value))
            handles.remove(oldest.key)
            sourceFiles.remove(oldest.key.second)
        }
        sourceFiles[asset] = stager(session).localFile(asset)
        val opened = try { await(PdfRequest.Open(asset, password)) as PdfReply.Opened } catch (failure: Exception) {
            sourceFiles.remove(asset)
            throw failure
        }
        handles[key] = opened.handle
        return opened.handle
    }

    /** A separate-document import receives a new blank session created by the library first. */
    @Synchronized
    fun importPdf(session: SessionKey, uri: Uri, asDocument: Boolean, password: String? = null): Outcome<RequestId, FeatureError> = attempt {
        workerOnly()
        require(runtime.latestViewport(session)?.isReadOnly != true) { "Notebook is read only" }
        val staging = stager(session)
        val staged = (if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Invalid file URI")
            java.io.FileInputStream(File(path))
        } else {
            context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not open stream for $uri")
        }).use { staging.stage(it, 268_435_456L, "pdf") }
        sourceFiles[staged.assetId] = staging.localFile(staged.assetId)
        val opened = try { await(PdfRequest.Open(staged.assetId, password)) as PdfReply.Opened } catch (failure: Exception) {
            sourceFiles.remove(staged.assetId)
            throw failure
        }
        try {
            require(opened.pageCount <= 10000) { "Choose a PDF page range for documents larger than 10000 pages" }
            val pages = (0 until opened.pageCount).map { index ->
                (await(PdfRequest.Info(opened.handle, index)) as PdfReply.PageInfo).page
            }
            val snapshot = requireNotNull(runtime.latestSnapshot(session)) { "Notebook closed during PDF import" }
            require(runtime.latestViewport(session)?.isReadOnly != true) { "Notebook became read only" }
            val replaceBlank = asDocument && snapshot.pages.size == 1 && snapshot.totalObjectCount == 0
            val plan = PdfImportPlanner.plan(snapshot, PdfImportAction.Pages(pages, replaceBlankInitial = replaceBlank), PlanContext(gestureId = GestureId("pdf-${java.util.UUID.randomUUID()}"))).getOrNull()
                ?: error("PDF page import validation failed")
            val submitted = runtime.trySend(AppCommand.SubmitEdit(session, plan, estimatedBytes = 1024L + plan.mutations.size * 512L, stagedAssets = listOf(staged)))
            submitted.getOrNull() ?: error(submitted.toString())
        } finally { runCatching { await(PdfRequest.Close(opened.handle)) }; sourceFiles.remove(staged.assetId) }
    }

    @Synchronized fun text(session: SessionKey, page: PdfPageRef, startIndex: Int = 0): Outcome<PdfReply.Text, FeatureError> = attempt {
        workerOnly(); await(PdfRequest.Text(handle(session, page.asset), page.pageIndex, startIndex = startIndex)) as PdfReply.Text
    }
    @Synchronized fun search(session: SessionKey, page: PdfPageRef, query: String): Outcome<PdfReply.Text, FeatureError> = attempt {
        workerOnly(); await(PdfRequest.Search(handle(session, page.asset), page.pageIndex, query)) as PdfReply.Text
    }
    @Synchronized fun links(session: SessionKey, page: PdfPageRef): Outcome<PdfReply.Links, FeatureError> = attempt {
        workerOnly(); await(PdfRequest.Links(handle(session, page.asset), page.pageIndex)) as PdfReply.Links
    }
    @Synchronized fun outline(session: SessionKey, asset: AssetId): Outcome<PdfReply.Outline, FeatureError> = attempt {
        workerOnly(); await(PdfRequest.Outline(handle(session, asset))) as PdfReply.Outline
    }

    @Synchronized
    fun selectedText(session: SessionKey, objectId: ObjectId, selectionWorld: DocRect): Outcome<String, FeatureError> = attempt {
        workerOnly()
        val obj = requireNotNull(runtime.latestSnapshot(session)?.getObject(objectId))
        val payload = obj.payload as PdfObjectPayload
        val mapping = PdfPageTransform(payload.page.cropBox, payload.page.rotation)
        val local = payload.localBounds ?: requireNotNull(obj.transform.invert()).transformRect(obj.bounds)
        val nativeHandle = handle(session, payload.page.asset)
        val output = StringBuilder()
        var index = 0
        while (true) {
            val reply = await(PdfRequest.Text(nativeHandle, payload.page.pageIndex, startIndex = index)) as PdfReply.Text
            for (span in reply.spans) {
                val localBox = DocRect(local.left + span.bounds.left / mapping.width * local.width,
                    local.top + span.bounds.top / mapping.height * local.height,
                    local.left + span.bounds.right / mapping.width * local.width,
                    local.top + span.bounds.bottom / mapping.height * local.height)
                if (obj.transform.transformRect(localBox).intersects(selectionWorld)) output.append(span.text)
            }
            require(output.length <= 2_000_000) { "PDF text selection exceeds copy budget" }
            if (!reply.truncated) break
            val next = requireNotNull(reply.nextIndex)
            require(next > index)
            index = next
        }
        output.toString()
    }

    @Synchronized
    fun objectLinks(session: SessionKey, objectId: ObjectId): Outcome<List<PdfLink>, FeatureError> = attempt {
        workerOnly()
        val obj = requireNotNull(runtime.latestSnapshot(session)?.getObject(objectId))
        val payload = obj.payload as PdfObjectPayload
        val mapping = PdfPageTransform(payload.page.cropBox, payload.page.rotation)
        val local = payload.localBounds ?: requireNotNull(obj.transform.invert()).transformRect(obj.bounds)
        val links = await(PdfRequest.Links(handle(session,payload.page.asset), payload.page.pageIndex)) as PdfReply.Links
        links.links.map { link ->
            val box = DocRect(local.left + link.bounds.left / mapping.width * local.width,
                local.top + link.bounds.top / mapping.height * local.height,
                local.left + link.bounds.right / mapping.width * local.width,
                local.top + link.bounds.bottom / mapping.height * local.height)
            link.copy(bounds = obj.transform.transformRect(box))
        }
    }

    @Synchronized
    fun tiles(session: SessionKey, snapshot: DocumentSnapshot, viewport: ViewportState): List<TileArtifact> {
        workerOnly()
        val visible = inkspace.render.CameraRelativeProjector.visibleWorldRect(viewport.camera, viewport.widthPx, viewport.heightPx)
        val activeSpace = snapshot.pages.find { it.id == viewport.activePageId }?.spaceId ?: snapshot.pages.firstOrNull()?.spaceId
        val result = mutableListOf<TileArtifact>()
        val objects = snapshot.queryBounds(visible).mapNotNull(snapshot::getObject)
            .filter { it.payload is PdfObjectPayload && it.space == activeSpace && it.style.isVisible && snapshot.getLayer(it.layer)?.isVisible == true }
            .sortedBy { it.style.zOrder }
        val pixelsPerResource = (4_194_304L / maxOf(1, objects.size)).coerceIn(1L, 1_048_576L)
        for (obj in objects) {
            val key = "pdf-object:${obj.id.value}:${snapshot.version.revision.value}:${viewport.camera}:${viewport.widthPx}x${viewport.heightPx}:${viewport.activePageId}:$pixelsPerResource:${generation.get()}"
            val cached = synchronized(cacheLock) {
                cache[key]?.let { entry ->
                    if (entry.lease.isClosed()) { cache.remove(key); cacheBytes -= entry.tile.byteSize; null } else entry
                }
            }
            if (cached != null && !cached.lease.isClosed()) { result += cached.tile; continue }
            val reservation = runtime.governor.tryReserve(MemoryCategory.PDF_WORKING_SET, 24L * 1024 * 1024, "pdf-tile-work").getOrNull() ?: continue
            try {
                val tile = buildTile(session, obj, viewport, visible, key, pixelsPerResource) ?: continue
                synchronized(cacheLock) {
                    if (!closed) {
                        while ((cacheBytes + tile.byteSize > 16L * 1024 * 1024 || cache.size >= 16) && cache.isNotEmpty()) evictFirst()
                        val lease = runtime.governor.tryReserve(MemoryCategory.CPU_RASTER_TILES, tile.byteSize, key, isEvictableOnTrim = true).getOrNull()
                        if (lease != null) {
                            cache[key] = Cached(tile, lease)
                            cacheBytes += tile.byteSize
                            result += tile
                        }
                    }
                }
            } finally { reservation.close() }
        }
        return result
    }

    private fun buildTile(session: SessionKey, obj: SceneObject, viewport: ViewportState, visible: DocRect, key: String, pixelBudget: Long): TileArtifact? {
        val payload = obj.payload as PdfObjectPayload
        val mapping = PdfPageTransform(payload.page.cropBox, payload.page.rotation)
        val bounds = intersection(obj.bounds, visible) ?: return null
        val inverse = obj.transform.invert() ?: return null
        val local = payload.localBounds ?: inverse.transformRect(obj.bounds)
        val localVisible = intersection(inverse.transformRect(bounds), local) ?: return null
        val region = DocRect((localVisible.left-local.left)/local.width*mapping.width, (localVisible.top-local.top)/local.height*mapping.height,
            (localVisible.right-local.left)/local.width*mapping.width, (localVisible.bottom-local.top)/local.height*mapping.height)
        val desiredWidth = ceil(bounds.width * viewport.camera.scale).coerceIn(1.0, 1024.0)
        val desiredHeight = ceil(bounds.height * viewport.camera.scale).coerceIn(1.0, 1024.0)
        val quality = sqrt(minOf(1.0, pixelBudget.toDouble() / (desiredWidth * desiredHeight)))
        val width = (desiredWidth * quality).toInt().coerceAtLeast(1)
        val height = (desiredHeight * quality).toInt().coerceAtLeast(1)
        val reply = await(PdfRequest.Tile(handle(session, payload.page.asset), payload.page.pageIndex, width, height, region)) as PdfReply.Tile
        val pixels = IntArray(width * height)
        ByteBuffer.wrap(reply.copyRgba()).order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels)
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            fun point(x: Double, y: Double): DocPoint {
                val px = local.left + (region.left + x / width * region.width) / mapping.width * local.width
                val py = local.top + (region.top + y / height * region.height) / mapping.height * local.height
                val world = obj.transform.transformPoint(DocPoint(px, py))
                return DocPoint((world.x-bounds.left)/bounds.width*width, (world.y-bounds.top)/bounds.height*height)
            }
            val zero = point(0.0, 0.0); val x = point(1.0, 0.0); val y = point(0.0, 1.0)
            val matrix = Matrix().apply { setValues(floatArrayOf((x.x-zero.x).toFloat(), (y.x-zero.x).toFloat(), zero.x.toFloat(), (x.y-zero.y).toFloat(), (y.y-zero.y).toFloat(), zero.y.toFloat(), 0f, 0f, 1f)) }
            val layerOpacity = runtime.latestSnapshot(session)?.getLayer(obj.layer)?.opacity ?: 1f
            Canvas(target).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * obj.style.opacity * layerOpacity).toInt().coerceIn(0, 255) })
            target.getPixels(pixels, 0, width, 0, 0, width, height)
            return TileArtifact(key, payload.page.asset, payload.page.pageIndex, bounds, width, height, inkspace.render.ScaleBucket.compute(viewport.camera.scale), pixels)
        } finally { source.recycle(); target.recycle() }
    }

    private fun intersection(a: DocRect, b: DocRect): DocRect? {
        val l = maxOf(a.left,b.left); val t = maxOf(a.top,b.top); val r = minOf(a.right,b.right); val d = minOf(a.bottom,b.bottom)
        return if (r > l && d > t) DocRect(l,t,r,d) else null
    }
    private fun evictFirst() { val entry = cache.entries.first(); entry.value.lease.close(); cacheBytes -= entry.value.tile.byteSize; cache.remove(entry.key) }
    private fun clearCache() { synchronized(cacheLock) { cache.values.forEach { it.lease.close() }; cache.clear(); cacheBytes = 0 } }
    private fun <T> attempt(block: () -> T): Outcome<T, FeatureError> = try { Outcome.Success(block()) } catch (failure: Exception) {
        Outcome.Failure(FeatureError.InvalidParameters((failure.message ?: "PDF operation failed").take(1024)))
    }
    override fun close() { if (!closed) { closed = true; clearCache(); sourceFiles.clear(); port.close(); synchronized(pendingLock) { pending.values.forEach { it.complete(PdfReply.Failed("PDF workspace closed")) }; pending.clear() } } }
}
