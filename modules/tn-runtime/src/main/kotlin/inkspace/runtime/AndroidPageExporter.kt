package inkspace.runtime

import android.graphics.Bitmap
import android.graphics.Canvas
import inkspace.core.error.RenderError
import inkspace.core.id.PageId
import inkspace.core.outcome.Outcome
import inkspace.core.payload.*
import inkspace.core.render.*
import inkspace.core.unit.DocRect
import inkspace.exchange.*
import inkspace.pdf.android.AndroidPdfWriter
import inkspace.pdf.android.PdfCompositionWriter
import inkspace.core.id.AssetId
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/** Execute on the Android renderer owner's worker with static image poster frames and a frozen scene. */
object AndroidPageExporter {
    fun export(scene: Scene, pages: List<PageId>, mode: ExportMode, output: ExportOutput,
        region: DocRect? = null, scale: Double = 1.0, canceled: () -> Boolean = { false },
        assetFile: ((AssetId) -> File)? = null,
        fallbackReport: (List<String>) -> Unit = {},
        render: (RenderRequest, Canvas?) -> Outcome<RenderReply, RenderError>) {
        require(pages.isNotEmpty() && pages.distinct().size == pages.size)
        require(scale.isFinite() && scale > 0 && scale <= 8)
        val selected = pages.map { scene.snapshot.getPage(it) ?: throw ExchangeException("Unknown page") }
        if (scene.frozenDraftStrokes.isNotEmpty() || scene.frozenEphemeralOverlays.isNotEmpty())
            throw ExchangeException("Acquire a frozen applied-revision scene without transient overlays")
        if (mode == ExportMode.Native) throw ExchangeException("Native output requires the archive writer")
        if (mode in setOf(ExportMode.PNG, ExportMode.JPEG) && pages.size != 1) throw ExchangeException("Choose one page for image output")
        SafeExport.write(output) { stream ->
            when (mode) {
                ExportMode.VectorPdf, ExportMode.FlattenedPdf, ExportMode.StandardAnnotations -> PdfCompositionWriter().use { writer ->
                    val snapshot = scene.snapshot
                    fun file(asset: AssetId) = assetFile?.invoke(asset) ?: throw ExchangeException("Source PDF asset resolver is required")
                    for (page in selected) {
                        if (canceled()) throw ExchangeException("Canceled")
                        val bounds = region ?: DocRect(0.0,0.0,page.widthPt,page.heightPt)
                        val target = writer.page(ceil(bounds.width).toFloat(),ceil(bounds.height).toFloat(),page.metadata.backgroundColor)
                        val source = page.metadata.pdfBackground
                        if(source != null) writer.source(target,file(source.asset),source.pageIndex,
                            (-bounds.left).toFloat(),(bounds.height-page.heightPt+bounds.top).toFloat(),page.widthPt.toFloat(),page.heightPt.toFloat(),mapNavigation = region == null)
                        else page.metadata.template?.asset?.let { asset ->
                            writer.source(target,file(asset),0,(-bounds.left).toFloat(),(bounds.height-page.heightPt+bounds.top).toFloat(),page.widthPt.toFloat(),page.heightPt.toFloat(),mapNavigation = region == null)
                        }
                        if(page.metadata.template?.grid?.kind?.let { it != inkspace.core.pages.PaperGrid.BLANK } == true) {
                            val bytes=ByteArrayOutputStream()
                            AndroidPdfWriter().use { overlay ->
                                overlay.page(ceil(bounds.width).toInt(),ceil(bounds.height).toInt()) { canvas ->
                                    if(render(RenderRequest.PdfOverlay(scene.copy(activeSpaceId=page.spaceId),page.id,bounds,emptySet()),canvas) is Outcome.Failure)
                                        throw ExchangeException("PDF paper replay failed")
                                }
                                overlay.write(bytes)
                            }
                            writer.overlay(target,bytes.toByteArray())
                        }
                        val layers = snapshot.layers.filter { it.pageId == page.id && it.isVisible && it.opacity > 0f }.map { it.id }.toSet()
                        val hidden = snapshot.directory.allResidentObjects().flatMap { (it.payload as? StickyPayload)?.takeIf { sticky -> sticky.isCollapsed }?.attachedIds.orEmpty() }.toSet()
                        val objects = snapshot.orderedSceneObjects().filter { it.layer in layers && it.style.isVisible && it.style.opacity > 0f && it.id !in hidden && it.payload !is GroupPayload }
                        val batches = if(mode == ExportMode.StandardAnnotations) objects.map { listOf(it) } else buildList {
                            var run = mutableListOf<inkspace.core.model.SceneObject>()
                            for(obj in objects) {
                                if(obj.payload is PdfObjectPayload || obj.payload is LinkAnchorPayload) {
                                    if(run.isNotEmpty()) { add(run.toList()); run.clear() }
                                    add(listOf(obj))
                                } else run.add(obj)
                            }
                            if(run.isNotEmpty()) add(run.toList())
                        }
                        for(batch in batches) {
                            val obj = batch.first()
                            if(canceled()) throw ExchangeException("Canceled")
                            val payload = obj.payload
                            if(payload is LinkAnchorPayload) {
                                writer.link(target,floatArrayOf((obj.bounds.left-bounds.left).toFloat(),(bounds.height-obj.bounds.bottom+bounds.top).toFloat(),obj.bounds.width.toFloat(),obj.bounds.height.toFloat()),
                                    payload.targetPage?.let { id -> pages.indexOf(id).takeIf { it >= 0 } },payload.targetUrl)
                                continue
                            }
                            if(payload is PdfObjectPayload) {
                                val opacity = obj.style.opacity * (snapshot.getLayer(obj.layer)?.opacity ?: 1f)
                                val local = payload.localBounds ?: obj.transform.invert()?.transformRect(obj.bounds) ?: obj.bounds
                                val t = obj.transform
                                writer.source(target,file(payload.page.asset),payload.page.pageIndex,
                                    local.left.toFloat(),(-local.bottom).toFloat(),local.width.toFloat(),local.height.toFloat(),opacity,mapNavigation = region == null,
                                    transform = floatArrayOf(t.m00.toFloat(),(-t.m10).toFloat(),(-t.m01).toFloat(),t.m11.toFloat(),
                                        (t.m02-bounds.left).toFloat(),(bounds.height+bounds.top-t.m12).toFloat()))
                                continue
                            }
                            val batchBounds = batch.map { it.bounds }.reduce { a,b -> a.union(b) }
                            if(!batchBounds.intersects(bounds)) continue
                            val objectBounds = DocRect(maxOf(batchBounds.left,bounds.left),maxOf(batchBounds.top,bounds.top),minOf(batchBounds.right,bounds.right),minOf(batchBounds.bottom,bounds.bottom))
                            val objectScene = scene.copy(activeSpaceId = page.spaceId,tiles = emptyList(),backgroundColorArgb = 0)
                            val bytes = ByteArrayOutputStream()
                            AndroidPdfWriter().use { overlay ->
                                overlay.page(ceil(objectBounds.width).toInt(),ceil(objectBounds.height).toInt()) { canvas ->
                                    if(render(RenderRequest.PdfOverlay(objectScene,page.id,objectBounds,batch.map { it.id }.toSet(),includePaper = false),canvas) is Outcome.Failure)
                                        throw ExchangeException("PDF shared Canvas replay failed")
                                }
                                overlay.write(bytes)
                            }
                            val standard = mode == ExportMode.StandardAnnotations
                            val subtype = if(standard) when(payload) {
                                is StrokePayload -> "Ink"
                                is StickyPayload -> "Text"
                                is ShapePayload -> when(payload.shapeType) {
                                    ShapeType.Rectangle, ShapeType.Square -> "Square"
                                    ShapeType.Circle, ShapeType.Ellipse -> "Circle"
                                    else -> null
                                }
                                else -> null
                            } else null
                            if(standard && subtype == null) writer.fallback("${payload::class.simpleName}: shared vector appearance; standard editable annotation unsupported")
                            if(batch.any { it.payload is TapePayload && (it.payload as TapePayload).pattern != "solid" }) writer.fallback("Tape texture uses shared Canvas appearance")
                            if(batch.any { it.payload is TextPayload || it.payload is StickyPayload }) writer.fallback("Text uses Android font shaping and PDF font embedding/substitution")
                            val ink = (payload as? StrokePayload)?.let { stroke -> stroke.samples.flatMap { sample ->
                                val point = obj.transform.transformPoint(inkspace.core.unit.DocPoint(stroke.origin.x+sample.relX,stroke.origin.y+sample.relY))
                                listOf((point.x-bounds.left).toFloat(),(bounds.height-point.y+bounds.top).toFloat())
                            }.toFloatArray() }
                            writer.overlay(target,bytes.toByteArray(),subtype,(payload as? StickyPayload)?.content ?: "",ink,
                                (objectBounds.left-bounds.left).toFloat(),(bounds.height-objectBounds.bottom+bounds.top).toFloat(),objectBounds.width.toFloat(),objectBounds.height.toFloat())
                        }
                    }
                    writer.write(stream)
                    fallbackReport(writer.fallbackReport)
                }
                ExportMode.PNG, ExportMode.JPEG -> {
                    val page = selected.single()
                    val bounds = region ?: DocRect(0.0, 0.0, page.widthPt, page.heightPt)
                    val width = ceil(bounds.width * scale).toInt()
                    val height = ceil(bounds.height * scale).toInt()
                    require(width in 1..32768 && height in 1..32768)
                    val pageScene = scene.copy(activeSpaceId = page.spaceId)
                    fun pixels(top: Int, rows: Int): IntArray {
                        if (canceled()) throw ExchangeException("Canceled")
                        val rect = DocRect(bounds.left, bounds.top + top / scale, bounds.left + width / scale, bounds.top + (top + rows) / scale)
                        val reply = render(RenderRequest.Snapshot(pageScene, rect, scale), null).getOrNull() as? RenderReply.SnapshotRendered
                            ?: throw ExchangeException("Image replay failed")
                        if (reply.widthPx != width || reply.heightPx != rows) throw ExchangeException("Raster dimensions mismatch")
                        return reply.copyPixels()
                    }
                    if (mode == ExportMode.PNG) StreamingPng.write(stream, width, height, rowsPerTile = minOf(128, maxOf(1, 262144 / width)), pixels = ::pixels)
                    else StreamingJpeg.write(stream,width,height,::pixels)
                }
                else -> throw ExchangeException("Unsupported output mode")
            }
            if (canceled()) throw ExchangeException("Canceled")
        }
    }
}
