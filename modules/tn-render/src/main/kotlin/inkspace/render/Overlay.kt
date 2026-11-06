package inkspace.render

import inkspace.core.error.RenderError
import inkspace.core.model.SceneObject
import inkspace.core.outcome.Outcome
import inkspace.core.ports.GeometryPort
import inkspace.core.render.DraftStrokeSceneItem
import inkspace.core.render.EphemeralOverlayItem
import inkspace.core.unit.CameraTransform

/**
 * Converts transient `DraftStrokeSceneItem` into a temporary `SceneObject` so it goes through
 * the exact same `DerivedArtifactCache` + `GeometryPort` + `CanvasReplay` path as committed strokes,
 * and replays ephemeral overlays (`SelectionBounds`, `HoverCursor`, `LaserTrail`).
 */
object OverlayRenderer {

    fun draftToSceneObject(draft: DraftStrokeSceneItem): SceneObject {
        return SceneObject(
            id = draft.draftId,
            space = draft.spaceId,
            layer = draft.layerId,
            bounds = draft.bounds,
            payload = draft.payload,
            transform = draft.transform,
            style = draft.style,
            isLocked = false,
            version = 1L
        )
    }

    fun resolveDraftGeometry(
        draft: DraftStrokeSceneItem,
        scaleBucket: Int,
        cache: DerivedArtifactCache,
        geometryPort: GeometryPort,
        onHit: () -> Unit,
        onRebuild: () -> Unit
    ): Outcome<CachedGeometryEntry, RenderError> {
        val asObj = draftToSceneObject(draft)
        return cache.getOrPutGeometry(
            objectId = asObj.id,
            contentVersion = asObj.version,
            payloadDigest = asObj.payloadDigest,
            payload = draft.payload,
            scaleBucket = scaleBucket,
            geometryPort = geometryPort,
            onHit = onHit,
            onRebuild = onRebuild
        )
    }

    fun drawEphemeralOverlays(
        overlays: List<EphemeralOverlayItem>,
        camera: CameraTransform,
        target: SoftwareArgbSurface
    ) {
        for (item in overlays) {
            when (item) {
                is EphemeralOverlayItem.SelectionBounds -> {
                    val viewRect = CameraRelativeProjector.projectWorldRectToView(item.rect, camera)
                    val strokePx = (item.strokeWidthPt * camera.scale).toFloat().coerceAtLeast(1.0f)
                    target.drawStrokeRect(viewRect, item.colorArgb, strokePx)
                }
                is EphemeralOverlayItem.HoverCursor -> {
                    val cx = ((item.point.x * camera.scale) + camera.translateX).toFloat()
                    val cy = ((item.point.y * camera.scale) + camera.translateY).toFloat()
                    val rPx = (item.radiusPt * camera.scale).toFloat().coerceAtLeast(2.0f)
                    target.drawFilledCircle(cx, cy, rPx, item.colorArgb)
                }
                is EphemeralOverlayItem.LaserTrail -> {
                    val pts = item.frozenPoints
                    if (pts.isNotEmpty()) {
                        val xs = FloatArray(pts.size)
                        val ys = FloatArray(pts.size)
                        for (i in pts.indices) {
                            xs[i] = (((item.origin.x + pts[i].x) * camera.scale) + camera.translateX).toFloat()
                            ys[i] = (((item.origin.y + pts[i].y) * camera.scale) + camera.translateY).toFloat()
                        }
                        val widthPx = (item.widthPt * camera.scale).toFloat().coerceAtLeast(1.0f)
                        target.drawPolyline(xs, ys, pts.size, item.colorArgb, widthPx, isHighlighter = false)
                    }
                }
                is EphemeralOverlayItem.RulerOverlay -> {
                    val state = item.state
                    if (state.isVisible) {
                        val angleRad = state.angleDegrees * (kotlin.math.PI / 180.0)
                        val cosA = kotlin.math.cos(angleRad)
                        val sinA = kotlin.math.sin(angleRad)
                        val halfL = state.lengthPt * 0.5
                        val halfW = state.thicknessPt * 0.5
                        val cXs = doubleArrayOf(-halfL, halfL, halfL, -halfL, -halfL)
                        val cYs = doubleArrayOf(-halfW, -halfW, halfW, halfW, -halfW)
                        val xs = FloatArray(5)
                        val ys = FloatArray(5)
                        for (i in 0..4) {
                            val wx = state.centerDoc.x + cXs[i] * cosA - cYs[i] * sinA
                            val wy = state.centerDoc.y + cXs[i] * sinA + cYs[i] * cosA
                            xs[i] = ((wx * camera.scale) + camera.translateX).toFloat()
                            ys[i] = ((wy * camera.scale) + camera.translateY).toFloat()
                        }
                        val strokePx = (1.5 * camera.scale).toFloat().coerceAtLeast(1.0f)
                        target.drawPolyline(xs, ys, 5, 0xCC1E293B.toInt(), strokePx, isHighlighter = false)
                    }
                }
                is EphemeralOverlayItem.ProtractorOverlay -> {
                    val state = item.state
                    if (state.isVisible) {
                        val strokePx = (1.5 * camera.scale).toFloat().coerceAtLeast(1.0f)
                        val stepCount = 36
                        val xs = FloatArray(stepCount + 1)
                        val ys = FloatArray(stepCount + 1)
                        val baseRad = state.baseAngleDegrees * (kotlin.math.PI / 180.0)
                        for (i in 0..stepCount) {
                            val a = (i.toDouble() / stepCount) * kotlin.math.PI
                            val worldX = state.centerDoc.x + state.radiusPt * kotlin.math.cos(a + baseRad)
                            val worldY = state.centerDoc.y + state.radiusPt * kotlin.math.sin(a + baseRad)
                            xs[i] = ((worldX * camera.scale) + camera.translateX).toFloat()
                            ys[i] = ((worldY * camera.scale) + camera.translateY).toFloat()
                        }
                        target.drawPolyline(xs, ys, stepCount + 1, 0xCC1E293B.toInt(), strokePx, isHighlighter = false)
                    }
                }
                is EphemeralOverlayItem.AlignmentGuidesOverlay -> {
                    val strokePx = 1.0f
                    for (guide in item.frozenGuides) {
                        val isVert = guide.kind == inkspace.core.objects.AlignmentGuideKind.CENTER_X ||
                            guide.kind == inkspace.core.objects.AlignmentGuideKind.LEFT_EDGE ||
                            guide.kind == inkspace.core.objects.AlignmentGuideKind.RIGHT_EDGE
                        val color = if (guide.isDashedCenterLine) 0xFF2563EB.toInt() else 0xFF10B981.toInt()
                        if (isVert) {
                            val vx = ((guide.coordinatePt * camera.scale) + camera.translateX).toFloat()
                            target.drawPolyline(
                                floatArrayOf(vx, vx),
                                floatArrayOf(0f, target.heightPx.toFloat()),
                                2,
                                color,
                                strokePx,
                                false
                            )
                        } else {
                            val vy = ((guide.coordinatePt * camera.scale) + camera.translateY).toFloat()
                            target.drawPolyline(
                                floatArrayOf(0f, target.widthPx.toFloat()),
                                floatArrayOf(vy, vy),
                                2,
                                color,
                                strokePx,
                                false
                            )
                        }
                    }
                }
            }
        }
    }
}
