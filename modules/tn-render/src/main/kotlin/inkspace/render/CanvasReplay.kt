package inkspace.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import inkspace.core.error.RenderError
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StickyPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.TapePayload
import inkspace.core.payload.TextPayload
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.TextPort
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.PdfObjectPayload
import inkspace.core.ports.ResourcePort
import inkspace.core.ports.ResourceRequest
import inkspace.core.model.SceneObject
import inkspace.core.unit.DocRect
import inkspace.core.unit.DocPoint
import inkspace.core.model.AffineTransform2D
import inkspace.core.render.TileArtifact
import inkspace.core.unit.CameraTransform
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class GoldenComparisonResult(
    val matches: Boolean,
    val maxNonEdgeChannelDelta: Int,
    val mismatchedPixelsCount: Int,
    val totalPixels: Int
)

/**
 * Deterministic sRGB ARGB_8888 image comparator.
 * Enforces antialiasing tolerance: <= 1 px spatial shift on edges, <= 2 per 8-bit channel outside edges.
 */
object GoldenImageComparator {

    fun compare(
        expectedArgb: IntArray,
        actualArgb: IntArray,
        widthPx: Int,
        heightPx: Int,
        channelTolerance: Int = 2
    ): GoldenComparisonResult {
        val total = widthPx * heightPx
        if (expectedArgb.size < total || actualArgb.size < total) {
            return GoldenComparisonResult(
                matches = false,
                maxNonEdgeChannelDelta = 255,
                mismatchedPixelsCount = total,
                totalPixels = total
            )
        }

        var maxNonEdgeDelta = 0
        var mismatched = 0

        for (y in 0 until heightPx) {
            val rowOffset = y * widthPx
            for (x in 0 until widthPx) {
                val idx = rowOffset + x
                val exp = expectedArgb[idx]
                val act = actualArgb[idx]
                val delta = maxChannelDelta(exp, act)
                if (delta <= channelTolerance) {
                    continue
                }
                if (isEdgePixel(expectedArgb, widthPx, heightPx, x, y, channelTolerance) &&
                    matchesWithin1PxNeighborhood(expectedArgb, widthPx, heightPx, x, y, act, channelTolerance)
                ) {
                    continue
                }
                if (delta > maxNonEdgeDelta) {
                    maxNonEdgeDelta = delta
                }
                mismatched += 1
            }
        }

        return GoldenComparisonResult(
            matches = mismatched == 0 && maxNonEdgeDelta <= channelTolerance,
            maxNonEdgeChannelDelta = maxNonEdgeDelta,
            mismatchedPixelsCount = mismatched,
            totalPixels = total
        )
    }

    private fun maxChannelDelta(c1: Int, c2: Int): Int {
        if (c1 == c2) return 0
        val da = abs(((c1 ushr 24) and 0xFF) - ((c2 ushr 24) and 0xFF))
        val dr = abs(((c1 ushr 16) and 0xFF) - ((c2 ushr 16) and 0xFF))
        val dg = abs(((c1 ushr 8) and 0xFF) - ((c2 ushr 8) and 0xFF))
        val db = abs((c1 and 0xFF) - (c2 and 0xFF))
        return max(max(da, dr), max(dg, db))
    }

    private fun isEdgePixel(
        img: IntArray,
        w: Int,
        h: Int,
        cx: Int,
        cy: Int,
        tol: Int
    ): Boolean {
        val center = img[cy * w + cx]
        val y0 = max(0, cy - 1)
        val y1 = min(h - 1, cy + 1)
        val x0 = max(0, cx - 1)
        val x1 = min(w - 1, cx + 1)
        for (ny in y0..y1) {
            for (nx in x0..x1) {
                if (maxChannelDelta(center, img[ny * w + nx]) > tol) {
                    return true
                }
            }
        }
        return false
    }

    private fun matchesWithin1PxNeighborhood(
        expected: IntArray,
        w: Int,
        h: Int,
        cx: Int,
        cy: Int,
        actualColor: Int,
        tol: Int
    ): Boolean {
        val y0 = max(0, cy - 1)
        val y1 = min(h - 1, cy + 1)
        val x0 = max(0, cx - 1)
        val x1 = min(w - 1, cx + 1)
        for (ny in y0..y1) {
            for (nx in x0..x1) {
                if (maxChannelDelta(expected[ny * w + nx], actualColor) <= tol) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * Software ARGB_8888 raster surface used by `CanvasReplay` for deterministic host/device rendering,
 * snapshot generation, and PDF preview verification.
 */
class SoftwareArgbSurface(
    val widthPx: Int,
    val heightPx: Int
) {
    val pixels: IntArray = IntArray(widthPx * heightPx)
    private var clipLeft: Int = 0
    private var clipTop: Int = 0
    private var clipRight: Int = widthPx
    private var clipBottom: Int = heightPx

    fun fill(colorArgb: Int) {
        pixels.fill(colorArgb)
    }

    fun setClipRect(rect: ViewFloatRect?) {
        if (rect == null) {
            clipLeft = 0
            clipTop = 0
            clipRight = widthPx
            clipBottom = heightPx
        } else {
            clipLeft = floor(rect.left).toInt().coerceIn(0, widthPx)
            clipTop = floor(rect.top).toInt().coerceIn(0, heightPx)
            clipRight = ceil(rect.right).toInt().coerceIn(clipLeft, widthPx)
            clipBottom = ceil(rect.bottom).toInt().coerceIn(clipTop, heightPx)
        }
    }

    fun drawFilledRect(rect: ViewFloatRect, colorArgb: Int) {
        val x0 = max(clipLeft, floor(rect.left).toInt())
        val y0 = max(clipTop, floor(rect.top).toInt())
        val x1 = min(clipRight, ceil(rect.right).toInt())
        val y1 = min(clipBottom, ceil(rect.bottom).toInt())
        if (x0 >= x1 || y0 >= y1) return

        for (y in y0 until y1) {
            val row = y * widthPx
            for (x in x0 until x1) {
                pixels[row + x] = blendSrcOver(pixels[row + x], colorArgb)
            }
        }
    }

    fun drawStrokeRect(rect: ViewFloatRect, colorArgb: Int, strokeWidthPx: Float) {
        val half = max(0.5f, strokeWidthPx * 0.5f)
        drawFilledRect(ViewFloatRect(rect.left - half, rect.top - half, rect.right + half, rect.top + half), colorArgb)
        drawFilledRect(ViewFloatRect(rect.left - half, rect.bottom - half, rect.right + half, rect.bottom + half), colorArgb)
        drawFilledRect(ViewFloatRect(rect.left - half, rect.top + half, rect.left + half, rect.bottom - half), colorArgb)
        drawFilledRect(ViewFloatRect(rect.right - half, rect.top + half, rect.right + half, rect.bottom - half), colorArgb)
    }

    fun drawFilledCircle(cx: Float, cy: Float, radiusPx: Float, colorArgb: Int) {
        val r = max(0.5f, radiusPx)
        val rSq = r * r
        val x0 = max(clipLeft, floor(cx - r).toInt())
        val y0 = max(clipTop, floor(cy - r).toInt())
        val x1 = min(clipRight - 1, ceil(cx + r).toInt())
        val y1 = min(clipBottom - 1, ceil(cy + r).toInt())
        if (x0 > x1 || y0 > y1) return

        for (y in y0..y1) {
            val dy = (y + 0.5f) - cy
            val row = y * widthPx
            for (x in x0..x1) {
                val dx = (x + 0.5f) - cx
                if (dx * dx + dy * dy <= rSq) {
                    pixels[row + x] = blendSrcOver(pixels[row + x], colorArgb)
                }
            }
        }
    }

    fun drawRaster(width: Int, height: Int, localRect: DocRect, transform: AffineTransform2D,
                   camera: CameraTransform, opacity: Float, pixelAt: (Int, Int) -> Int,
                   mask: ((Double, Double) -> Boolean)? = null) {
        if (localRect.width <= 0 || localRect.height <= 0) return
        val inverse = transform.invert() ?: return
        val projected = CameraRelativeProjector.projectWorldRectToView(transform.transformRect(localRect), camera)
        val left = floor(projected.left).toInt().coerceIn(clipLeft, clipRight)
        val top = floor(projected.top).toInt().coerceIn(clipTop, clipBottom)
        val right = ceil(projected.right).toInt().coerceIn(clipLeft, clipRight)
        val bottom = ceil(projected.bottom).toInt().coerceIn(clipTop, clipBottom)
        for (y in top until bottom) for (x in left until right) {
            val worldX = (x + 0.5 - camera.translateX) / camera.scale
            val worldY = (y + 0.5 - camera.translateY) / camera.scale
            val localX = inverse.m00 * worldX + inverse.m01 * worldY + inverse.m02
            val localY = inverse.m10 * worldX + inverse.m11 * worldY + inverse.m12
            val u = (localX - localRect.left) / localRect.width
            val v = (localY - localRect.top) / localRect.height
            if (u < 0 || v < 0 || u >= 1 || v >= 1 || mask?.invoke(u, v) == false) continue
            val color = modulateAlpha(pixelAt((u * width).toInt().coerceIn(0,width-1), (v * height).toInt().coerceIn(0,height-1)), opacity)
            pixels[y * widthPx + x] = blendSrcOver(pixels[y * widthPx + x], color)
        }
    }

    fun drawTile(tile: TileArtifact, destRect: ViewFloatRect) {
        val x0 = max(clipLeft, floor(destRect.left).toInt())
        val y0 = max(clipTop, floor(destRect.top).toInt())
        val x1 = min(clipRight, ceil(destRect.right).toInt())
        val y1 = min(clipBottom, ceil(destRect.bottom).toInt())
        val destW = (destRect.width).coerceAtLeast(1.0f)
        val destH = (destRect.height).coerceAtLeast(1.0f)
        if (x0 >= x1 || y0 >= y1) return

        for (y in y0 until y1) {
            val ty = (((y - destRect.top) / destH) * tile.heightPx).toInt().coerceIn(0, tile.heightPx - 1)
            val row = y * widthPx
            for (x in x0 until x1) {
                val tx = (((x - destRect.left) / destW) * tile.widthPx).toInt().coerceIn(0, tile.widthPx - 1)
                val srcColor = tile.pixelAt(tx, ty)
                pixels[row + x] = blendSrcOver(pixels[row + x], srcColor)
            }
        }
    }

    fun drawPolyline(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        colorArgb: Int,
        strokeWidthPx: Float,
        isHighlighter: Boolean
    ) {
        if (count <= 0) return
        val radius = max(0.75f, strokeWidthPx * 0.5f)
        val radiusSq = radius * radius

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val x = xs[i]
            val y = ys[i]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }

        val bx0 = max(clipLeft, floor(minX - radius).toInt())
        val by0 = max(clipTop, floor(minY - radius).toInt())
        val bx1 = min(clipRight - 1, ceil(maxX + radius).toInt())
        val by1 = min(clipBottom - 1, ceil(maxY + radius).toInt())
        if (bx0 > bx1 || by0 > by1) return

        val boxW = bx1 - bx0 + 1
        val boxH = by1 - by0 + 1
        // Highlighter strokes composite with a per-stroke mask so self-intersections never double-darken
        val mask = if (isHighlighter || count > 1) BooleanArray(boxW * boxH) else null

        if (count == 1) {
            drawFilledCircle(xs[0], ys[0], radius, colorArgb)
            return
        }

        for (seg in 0 until count - 1) {
            val ax = xs[seg]
            val ay = ys[seg]
            val bx = xs[seg + 1]
            val by = ys[seg + 1]

            val sx0 = max(bx0, floor(min(ax, bx) - radius).toInt())
            val sy0 = max(by0, floor(min(ay, by) - radius).toInt())
            val sx1 = min(bx1, ceil(max(ax, bx) + radius).toInt())
            val sy1 = min(by1, ceil(max(ay, by) + radius).toInt())

            val abx = bx - ax
            val aby = by - ay
            val abLenSq = abx * abx + aby * aby

            for (py in sy0..sy1) {
                val cy = py + 0.5f
                val row = py * widthPx
                val maskRow = (py - by0) * boxW
                for (px in sx0..sx1) {
                    val maskIdx = maskRow + (px - bx0)
                    if (mask != null && mask[maskIdx]) continue

                    val cx = px + 0.5f
                    val t = if (abLenSq > 1e-6f) {
                        (((cx - ax) * abx + (cy - ay) * aby) / abLenSq).coerceIn(0.0f, 1.0f)
                    } else {
                        0.0f
                    }
                    val nearX = ax + t * abx
                    val nearY = ay + t * aby
                    val dx = cx - nearX
                    val dy = cy - nearY
                    if (dx * dx + dy * dy <= radiusSq) {
                        if (mask != null) {
                            mask[maskIdx] = true
                        }
                        pixels[row + px] = blendSrcOver(pixels[row + px], colorArgb)
                    }
                }
            }
        }
    }

    companion object {
        fun modulateAlpha(colorArgb: Int, factor: Float): Int {
            val f = factor.coerceIn(0.0f, 1.0f)
            if (f >= 0.999f) return colorArgb
            val baseA = (colorArgb ushr 24) and 0xFF
            val newA = (baseA * f).roundToInt().coerceIn(0, 255)
            return (colorArgb and 0x00FFFFFF) or (newA shl 24)
        }

        fun blendSrcOver(dstArgb: Int, srcArgb: Int): Int {
            val srcA = (srcArgb ushr 24) and 0xFF
            if (srcA == 255) return srcArgb
            if (srcA == 0) return dstArgb

            val invA = 255 - srcA
            val srcR = (srcArgb ushr 16) and 0xFF
            val srcG = (srcArgb ushr 8) and 0xFF
            val srcB = srcArgb and 0xFF

            val dstA = (dstArgb ushr 24) and 0xFF
            val dstR = (dstArgb ushr 16) and 0xFF
            val dstG = (dstArgb ushr 8) and 0xFF
            val dstB = dstArgb and 0xFF

            val outA = srcA + ((dstA * invA + 127) / 255)
            val outR = (srcR * srcA + dstR * invA + 127) / 255
            val outG = (srcG * srcA + dstG * invA + 127) / 255
            val outB = (srcB * srcA + dstB * invA + 127) / 255

            return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
    }
}

data class ReplayCounters(
    var drawnStrokes: Int = 0,
    var drawnShapes: Int = 0,
    var drawnTexts: Int = 0,
    var drawnTiles: Int = 0,
    var drawnDrafts: Int = 0,
    var geometryHits: Int = 0,
    var geometryRebuilds: Int = 0,
    var textHits: Int = 0,
    var textRebuilds: Int = 0,
    var tileHits: Int = 0
)

/**
 * Single unified drawing replay engine shared across:
 * - Screen `Surface` (`Draw`)
 * - Raster `Snapshot` (`Snapshot`)
 * - Vector/preview `PdfDocument` (`PdfOverlay`)
 *
 * Each executor (render vs export) instantiates its own `CanvasReplay` so mutable `Paint`/`Path`/buffers
 * are never shared across threads.
 */
class CanvasReplay {

    private var projXs = FloatArray(256)
    private var projYs = FloatArray(256)
    private val androidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val androidPath = Path()

    private fun ensureScratchCapacity(count: Int) {
        if (projXs.size < count) {
            val nextSize = max(count, projXs.size * 2)
            projXs = FloatArray(nextSize)
            projYs = FloatArray(nextSize)
        }
    }

    fun replay(
        prepared: PreparedScene,
        camera: CameraTransform,
        targetWidthPx: Int,
        targetHeightPx: Int,
        computePixels: Boolean,
        derivedCache: DerivedArtifactCache,
        tileCache: RasterTileCache,
        geometryPort: GeometryPort,
        textPort: TextPort,
        androidCanvas: Canvas? = null,
        pdfVectorCommandsOut: StringBuilder? = null,
        resourcePort: ResourcePort? = null
    ): Outcome<Pair<ReplayCounters, IntArray?>, RenderError> {
        val counters = ReplayCounters()
        val softwareSurface = if (computePixels) {
            SoftwareArgbSurface(targetWidthPx, targetHeightPx).apply {
                fill(prepared.scene.backgroundColorArgb)
            }
        } else {
            null
        }

        androidCanvas?.drawColor(prepared.scene.backgroundColorArgb)
        for (page in prepared.visiblePages) {
            val bounds = CameraRelativeProjector.projectWorldRectToView(page.worldBounds,camera)
            softwareSurface?.drawFilledRect(bounds,page.descriptor.metadata.backgroundColor)
            softwareSurface?.setClipRect(bounds)
            val grid=page.descriptor.metadata.template?.grid
            if(grid!=null && grid.kind!=inkspace.core.pages.PaperGrid.BLANK && grid.spacingPt*camera.scale>=4) {
                val spacing=(grid.spacingPt*camera.scale).toFloat()
                val firstX=max(bounds.left,0f); val firstY=max(bounds.top,0f)
                val lastX=min(bounds.right,targetWidthPx.toFloat()); val lastY=min(bounds.bottom,targetHeightPx.toFloat())
                val startX=bounds.left+floor((firstX-bounds.left)/spacing)*spacing
                val startY=bounds.top+floor((firstY-bounds.top)/spacing)*spacing
                var y=startY
                var rows=0
                while(y<=lastY && rows++<5000) {
                    if(grid.kind==inkspace.core.pages.PaperGrid.DOT) {
                        var x=startX;var columns=0
                        while(x<=lastX && columns++<5000) { softwareSurface?.drawFilledCircle(x,y,0.7f,grid.color);x+=spacing }
                    } else softwareSurface?.drawPolyline(floatArrayOf(firstX,lastX),floatArrayOf(y,y),2,grid.color,0.6f,false)
                    y+=spacing
                }
                if(grid.kind in setOf(inkspace.core.pages.PaperGrid.SQUARE,inkspace.core.pages.PaperGrid.MILLIMETER)) {
                    var x=startX;var columns=0
                    while(x<=lastX && columns++<5000) { softwareSurface?.drawPolyline(floatArrayOf(x,x),floatArrayOf(firstY,lastY),2,grid.color,0.6f,false);x+=spacing }
                }
                if(grid.kind==inkspace.core.pages.PaperGrid.ISOMETRIC) {
                    val height=lastY-firstY
                    var x=startX-height*0.57735f;var columns=0
                    while(x<=lastX+height && columns++<5000) {
                        softwareSurface?.drawPolyline(floatArrayOf(x,x+height*0.57735f),floatArrayOf(firstY,lastY),2,grid.color,0.6f,false)
                        softwareSurface?.drawPolyline(floatArrayOf(x,x-height*0.57735f),floatArrayOf(firstY,lastY),2,grid.color,0.6f,false)
                        x+=spacing
                    }
                }
            }
            softwareSurface?.setClipRect(null)
        }

        val clipViewRect = prepared.pageClipUnionWorld?.let {
            CameraRelativeProjector.projectWorldRectToView(it, camera)
        }
        softwareSurface?.setClipRect(clipViewRect)
        if (androidCanvas != null && clipViewRect != null) {
            androidCanvas.save()
            androidCanvas.clipRect(clipViewRect.left, clipViewRect.top, clipViewRect.right, clipViewRect.bottom)
        }

        // 1. Replay visible raster/PDF tiles
        for (tile in prepared.visibleTiles.filterNot { it.tileKey.startsWith("pdf-object:") }) {
            val admitted = tileCache.getOrAdmit(tile) { counters.tileHits += 1 }
            val cachedTile = when (admitted) {
                is Outcome.Failure -> return admitted
                is Outcome.Success -> admitted.value
            }
            val destRect = CameraRelativeProjector.projectWorldRectToView(cachedTile.docBounds, camera)
            softwareSurface?.drawTile(cachedTile, destRect)
            counters.drawnTiles += 1
        }

        // 2. Replay visible layers and their ordered scene objects + draft strokes
        for (group in prepared.layerGroups) {
            val layerOpacity = group.layer.opacity.coerceIn(0.0f, 1.0f)
            for (obj in group.objects) {
                val effectiveOpacity = (layerOpacity * obj.style.opacity).coerceIn(0.0f, 1.0f)
                when (val payload = obj.payload) {
                    is StrokePayload -> {
                        val geoOutcome = derivedCache.getOrPutGeometry(
                            objectId = obj.id,
                            contentVersion = obj.version,
                            payloadDigest = obj.payloadDigest,
                            payload = payload,
                            scaleBucket = prepared.scaleBucket,
                            geometryPort = geometryPort,
                            onHit = { counters.geometryHits += 1 },
                            onRebuild = { counters.geometryRebuilds += 1 }
                        )
                        val geo = when (geoOutcome) {
                            is Outcome.Failure -> return geoOutcome
                            is Outcome.Success -> geoOutcome.value
                        }
                        drawCachedStroke(
                            geo = geo,
                            payload = payload,
                            transform = obj.transform,
                            camera = camera,
                            opacityFactor = effectiveOpacity,
                            softwareSurface = softwareSurface,
                            androidCanvas = androidCanvas,
                            pdfCommands = pdfVectorCommandsOut
                        )
                        counters.drawnStrokes += 1
                    }
                    is ShapePayload -> {
                        val viewRect = CameraRelativeProjector.projectWorldRectToView(obj.bounds, camera)
                        val fill = payload.fillColor
                        if (fill != null) {
                            val fillMod = SoftwareArgbSurface.modulateAlpha(fill, effectiveOpacity)
                            softwareSurface?.drawFilledRect(viewRect, fillMod)
                        }
                        val strokeMod = SoftwareArgbSurface.modulateAlpha(payload.strokeColor, effectiveOpacity)
                        val widthPx = (payload.strokeWidthPt * camera.scale).toFloat().coerceAtLeast(1.0f)
                        if (payload.shapeType == ShapeType.Ellipse) {
                            val r = min(viewRect.width, viewRect.height) * 0.5f
                            softwareSurface?.drawFilledCircle(
                                (viewRect.left + viewRect.right) * 0.5f,
                                (viewRect.top + viewRect.bottom) * 0.5f,
                                r,
                                fill ?: strokeMod
                            )
                        } else {
                            softwareSurface?.drawStrokeRect(viewRect, strokeMod, widthPx)
                        }
                        pdfVectorCommandsOut?.append(
                            String.format(
                                Locale.US,
                                "%.2f %.2f %.2f %.2f re S\n",
                                viewRect.left,
                                viewRect.top,
                                viewRect.width,
                                viewRect.height
                            )
                        )
                        payload.label?.let { label ->
                            val result = drawText(obj,label,effectiveOpacity,prepared,camera,softwareSurface,androidCanvas,derivedCache,textPort,counters)
                            if (result is Outcome.Failure) return result
                        }
                        counters.drawnShapes += 1
                    }
                    is TextPayload -> {
                        val result = drawText(obj, payload, effectiveOpacity, prepared, camera, softwareSurface,
                            androidCanvas, derivedCache, textPort, counters)
                        if (result is Outcome.Failure) return result
                    }
                    is PdfObjectPayload -> {
                        val prefix="pdf-object:${obj.id.value}:"
                        for(tile in prepared.visibleTiles.filter { it.tileKey.startsWith(prefix) }) {
                            softwareSurface?.drawRaster(tile.widthPx,tile.heightPx,tile.docBounds,AffineTransform2D.IDENTITY,camera,effectiveOpacity,tile::pixelAt)
                            counters.drawnTiles++
                        }
                    }
                    is ImagePayload -> {
                        val request = ResourceRequest(payload.asset, 32L * 1024 * 1024,
                            targetWidthPx = (obj.bounds.width * camera.scale).toInt().coerceIn(1,2048),
                            targetHeightPx = (obj.bounds.height * camera.scale).toInt().coerceIn(1,2048),posterFrame=payload.posterFrame)
                        val lease = resourcePort?.read(request)?.getOrNull()
                        lease?.use {
                            val tile = it.imageTile
                            if (tile != null) {
                                val local = payload.containerBounds ?: obj.transform.invert()?.transformRect(obj.bounds) ?: obj.bounds
                                val crop = payload.cropRect ?: DocRect(0.0,0.0,payload.naturalWidthPx.toDouble(),payload.naturalHeightPx.toDouble())
                                softwareSurface?.drawRaster(tile.widthPx,tile.heightPx,local,obj.transform,camera,effectiveOpacity,
                                    { x,y -> tile.pixelAt(x,y) }, { u,v ->
                                        val px = u * payload.naturalWidthPx; val py = v * payload.naturalHeightPx
                                        px >= crop.left && px <= crop.right && py >= crop.top && py <= crop.bottom &&
                                            (payload.cropMask.isEmpty() || insidePolygon(px,py,payload.cropMask))
                                    })
                                counters.drawnTiles++
                            }
                        }
                    }
                    is StickyPayload -> {
                        val viewRect = CameraRelativeProjector.projectWorldRectToView(obj.bounds, camera)
                        val colorMod = SoftwareArgbSurface.modulateAlpha(payload.color, effectiveOpacity)
                        softwareSurface?.drawFilledRect(viewRect, colorMod)
                        if (!payload.isCollapsed) {
                            val text = payload.richText ?: TextPayload(payload.content)
                            val result = drawText(obj,text,effectiveOpacity,prepared,camera,softwareSurface,androidCanvas,derivedCache,textPort,counters)
                            if (result is Outcome.Failure) return result
                        }
                        counters.drawnShapes += 1
                    }
                    is TapePayload -> {
                        val viewRect = CameraRelativeProjector.projectWorldRectToView(obj.bounds, camera)
                        val colorMod = SoftwareArgbSurface.modulateAlpha(
                            payload.patternColor,
                            effectiveOpacity * payload.opacity
                        )
                        if (obj.id !in prepared.scene.revealedTapeIds) {
                            if (payload.points.size >= 2) {
                                val xs = FloatArray(payload.points.size)
                                val ys = FloatArray(payload.points.size)
                                payload.points.forEachIndexed { index, point ->
                                    val world = obj.transform.transformPoint(point)
                                    xs[index] = (world.x * camera.scale + camera.translateX).toFloat()
                                    ys[index] = (world.y * camera.scale + camera.translateY).toFloat()
                                }
                                softwareSurface?.drawPolyline(xs,ys,xs.size,colorMod,(payload.widthPt * camera.scale).toFloat(),false)
                                if (payload.pattern != "solid") {
                                    val detail = SoftwareArgbSurface.modulateAlpha(0xff000000.toInt(),effectiveOpacity * .2f)
                                    for (index in xs.indices step if(payload.pattern == "dotted") 2 else 3) softwareSurface?.drawFilledCircle(xs[index],ys[index],2f,detail)
                                }
                            } else softwareSurface?.drawFilledRect(viewRect, colorMod)
                        }
                        counters.drawnShapes += 1
                    }
                    else -> {}
                }
            }

            // Transient draft strokes on this layer (identical pipeline to committed strokes!)
            for (draft in group.draftStrokes) {
                val effectiveOpacity = (layerOpacity * draft.style.opacity).coerceIn(0.0f, 1.0f)
                val geoOutcome = OverlayRenderer.resolveDraftGeometry(
                    draft = draft,
                    scaleBucket = prepared.scaleBucket,
                    cache = derivedCache,
                    geometryPort = geometryPort,
                    onHit = { counters.geometryHits += 1 },
                    onRebuild = { counters.geometryRebuilds += 1 }
                )
                val geo = when (geoOutcome) {
                    is Outcome.Failure -> return geoOutcome
                    is Outcome.Success -> geoOutcome.value
                }
                drawCachedStroke(
                    geo = geo,
                    payload = draft.payload,
                    transform = draft.transform,
                    camera = camera,
                    opacityFactor = effectiveOpacity,
                    softwareSurface = softwareSurface,
                    androidCanvas = androidCanvas,
                    pdfCommands = pdfVectorCommandsOut
                )
                counters.drawnDrafts += 1
            }
        }

        // 3. Ephemeral overlays (selection bounds, hover, laser)
        if (softwareSurface != null && prepared.scene.frozenEphemeralOverlays.isNotEmpty()) {
            softwareSurface.setClipRect(null)
            OverlayRenderer.drawEphemeralOverlays(
                overlays = prepared.scene.frozenEphemeralOverlays,
                camera = camera,
                target = softwareSurface
            )
        }

        if (androidCanvas != null && clipViewRect != null) {
            androidCanvas.restore()
        }

        return Outcome.Success(counters to softwareSurface?.pixels)
    }

    private fun drawText(obj: SceneObject, payload: TextPayload, opacity: Float, prepared: PreparedScene,
        camera: CameraTransform, software: SoftwareArgbSurface?, canvas: Canvas?, cache: DerivedArtifactCache,
        port: TextPort, counters: ReplayCounters): Outcome<Unit,RenderError> {
        val container = payload.containerBounds ?: obj.transform.invert()?.transformRect(obj.bounds) ?: obj.bounds
        val result = cache.getOrPutTextLayout(obj.id,obj.version,inkspace.core.codec.CoreCodec.computePayloadDigest(payload),payload,
            container.width.coerceAtLeast(1.0),port,{ counters.textHits++ },{ counters.textRebuilds++ })
        if (result is Outcome.Failure) return result
        val layout = result.getOrNull()!!.layout
        val artifact = layout.paintPlan
        if (artifact != null) {
            if (software != null) {
                val inverse = obj.transform.invert() ?: return Outcome.Success(Unit)
                val visible = inverse.transformRect(prepared.visibleWorldRect)
                val local = DocRect(container.left,container.top,container.left+layout.widthPt,container.top+layout.heightPt)
                val left = max(local.left,visible.left); val top = max(local.top,visible.top)
                val right = min(local.right,visible.right); val bottom = min(local.bottom,visible.bottom)
                if (left < right && top < bottom) {
                    val region = DocRect(left-container.left,top-container.top,right-container.left,bottom-container.top)
                    val scale = min(1.0,sqrt(500_000.0 / (region.width * region.height).coerceAtLeast(1.0)))
                    val raster = artifact.rasterizeRegion(scale,524288,region)
                    software.drawRaster(raster.width,raster.height,DocRect(left,top,right,bottom),obj.transform,camera,opacity,raster::pixelAt)
                }
            }
            if (canvas != null) {
                canvas.save()
                canvas.translate(camera.translateX.toFloat(),camera.translateY.toFloat())
                canvas.scale(camera.scale.toFloat(),camera.scale.toFloat())
                val t = obj.transform
                val matrix = android.graphics.Matrix().apply { setValues(floatArrayOf(t.m00.toFloat(),t.m01.toFloat(),t.m02.toFloat(),t.m10.toFloat(),t.m11.toFloat(),t.m12.toFloat(),0f,0f,1f)) }
                canvas.concat(matrix); canvas.translate(container.left.toFloat(),container.top.toFloat())
                val layer = canvas.saveLayerAlpha(null,(opacity * 255).toInt())
                artifact.draw(canvas); canvas.restoreToCount(layer); canvas.restore()
            }
        } else {
            // Host metric-only ports have no glyph artifact; Android production always supplies one.
            val rect = CameraRelativeProjector.projectWorldRectToView(obj.bounds,camera)
            software?.drawFilledRect(ViewFloatRect(rect.left,rect.top,rect.right,min(rect.bottom,rect.top+(payload.fontSizePt*camera.scale).toFloat())),SoftwareArgbSurface.modulateAlpha(payload.fontColor,opacity))
        }
        counters.drawnTexts++
        return Outcome.Success(Unit)
    }
    private fun insidePolygon(x: Double,y: Double,points: List<DocPoint>): Boolean {
        var inside = false
        var previous = points.last()
        for (point in points) {
            if ((point.y > y) != (previous.y > y) && x < (previous.x-point.x)*(y-point.y)/(previous.y-point.y)+point.x) inside = !inside
            previous = point
        }
        return inside
    }

    private fun drawCachedStroke(
        geo: CachedGeometryEntry,
        payload: StrokePayload,
        transform: inkspace.core.model.AffineTransform2D,
        camera: CameraTransform,
        opacityFactor: Float,
        softwareSurface: SoftwareArgbSurface?,
        androidCanvas: Canvas?,
        pdfCommands: StringBuilder?
    ) {
        val count = geo.relXs.size
        if (count <= 0) return

        val isHighlighter = payload.brushId.equals("highlighter", ignoreCase = true)
        val colorMod = SoftwareArgbSurface.modulateAlpha(payload.color, opacityFactor)
        val widthPx = (payload.baseWidthPt * camera.scale).toFloat().coerceAtLeast(1.0f)

        // Only project sample points when pixel rasterization, Android Canvas, or PDF vector output is requested
        if (softwareSurface != null || androidCanvas != null || pdfCommands != null) {
            ensureScratchCapacity(count)
            for (i in 0 until count) {
                projXs[i] = CameraRelativeProjector.projectPointToViewX(
                    originX = geo.originX,
                    originY = geo.originY,
                    relX = geo.relXs[i].toDouble(),
                    relY = geo.relYs[i].toDouble(),
                    transform = transform,
                    camera = camera
                )
                projYs[i] = CameraRelativeProjector.projectPointToViewY(
                    originX = geo.originX,
                    originY = geo.originY,
                    relX = geo.relXs[i].toDouble(),
                    relY = geo.relYs[i].toDouble(),
                    transform = transform,
                    camera = camera
                )
            }

            softwareSurface?.drawPolyline(
                xs = projXs,
                ys = projYs,
                count = count,
                colorArgb = colorMod,
                strokeWidthPx = widthPx,
                isHighlighter = isHighlighter
            )

            if (androidCanvas != null) {
                androidPaint.color = colorMod
                androidPaint.strokeWidth = widthPx
                androidPaint.style = Paint.Style.STROKE
                androidPath.reset()
                androidPath.moveTo(projXs[0], projYs[0])
                for (i in 1 until count) {
                    androidPath.lineTo(projXs[i], projYs[i])
                }
                androidCanvas.drawPath(androidPath, androidPaint)
            }

            if (pdfCommands != null) {
                pdfCommands.append(String.format(Locale.US, "q %.2f w %.2f %.2f m", widthPx, projXs[0], projYs[0]))
                for (i in 1 until count) {
                    pdfCommands.append(String.format(Locale.US, " %.2f %.2f l", projXs[i], projYs[i]))
                }
                pdfCommands.append(" S Q\n")
            }
        }
    }

    companion object {
        fun buildPdfOverlayBytes(widthPt: Int, heightPt: Int, vectorStream: String): ByteArray {
            val pdfText = buildString(vectorStream.length + 256) {
                append("%PDF-1.4\n")
                append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
                append("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n")
                append("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 $widthPt $heightPt] /Contents 4 0 R >> endobj\n")
                append("4 0 obj << /Length ${vectorStream.length} >>\nstream\n")
                append(vectorStream)
                append("endstream\nendobj\n%%EOF\n")
            }
            return pdfText.encodeToByteArray()
        }
    }
}
