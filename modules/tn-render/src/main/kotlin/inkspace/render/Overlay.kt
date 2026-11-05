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
            }
        }
    }
}
