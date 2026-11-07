package inkspace.render

import inkspace.core.id.ObjectId
import inkspace.core.id.LayerId
import inkspace.core.id.PageId
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.render.DraftStrokeSceneItem
import inkspace.core.render.Scene
import inkspace.core.render.TileArtifact
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.core.unit.MAX_CAMERA_SCALE
import inkspace.core.unit.MIN_CAMERA_SCALE
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Screen-space Float rectangle after camera-relative Double->Float projection.
 */
data class ViewFloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Quantizes camera scale into logarithmic buckets (~6.25% step) so:
 * - Pure camera pan (translateX/translateY change, same scale) never changes scaleBucket.
 * - Sub-pixel zoom jitter stays within the same bucket (error bound <= 0.5 px).
 */
object ScaleBucket {
    private val LOG_STEP: Double = ln(1.0625)

    fun compute(scale: Double): Int {
        val clamped = scale.coerceIn(MIN_CAMERA_SCALE, MAX_CAMERA_SCALE)
        return (ln(clamped) / LOG_STEP).roundToInt()
    }
}

/**
 * Performs camera-relative coordinate projection in Double precision before converting to Float.
 * Prevents Float mantissa cancellation when world coordinates are large (e.g. 1e7 pt).
 */
object CameraRelativeProjector {

    fun applyAffineX(x: Double, y: Double, t: AffineTransform2D): Double {
        return t.m00 * x + t.m01 * y + t.m02
    }

    fun applyAffineY(x: Double, y: Double, t: AffineTransform2D): Double {
        return t.m10 * x + t.m11 * y + t.m12
    }

    fun projectPointToViewX(
        originX: Double,
        originY: Double,
        relX: Double,
        relY: Double,
        transform: AffineTransform2D,
        camera: CameraTransform
    ): Float {
        val docX = originX + relX
        val docY = originY + relY
        val worldX = applyAffineX(docX, docY, transform)
        return ((worldX * camera.scale) + camera.translateX).toFloat()
    }

    fun projectPointToViewY(
        originX: Double,
        originY: Double,
        relX: Double,
        relY: Double,
        transform: AffineTransform2D,
        camera: CameraTransform
    ): Float {
        val docX = originX + relX
        val docY = originY + relY
        val worldY = applyAffineY(docX, docY, transform)
        return ((worldY * camera.scale) + camera.translateY).toFloat()
    }

    fun projectWorldRectToView(rect: DocRect, camera: CameraTransform): ViewFloatRect {
        val l = ((rect.left * camera.scale) + camera.translateX).toFloat()
        val t = ((rect.top * camera.scale) + camera.translateY).toFloat()
        val r = ((rect.right * camera.scale) + camera.translateX).toFloat()
        val b = ((rect.bottom * camera.scale) + camera.translateY).toFloat()
        return ViewFloatRect(
            left = min(l, r),
            top = min(t, b),
            right = max(l, r),
            bottom = max(t, b)
        )
    }

    fun visibleWorldRect(
        camera: CameraTransform,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ): DocRect {
        val p0 = camera.viewToDoc(inkspace.core.unit.ViewPoint(0.0, 0.0))
        val p1 = camera.viewToDoc(
            inkspace.core.unit.ViewPoint(viewportWidthPx.toDouble(), viewportHeightPx.toDouble())
        )
        return DocRect(
            left = min(p0.x, p1.x),
            top = min(p0.y, p1.y),
            right = max(p0.x, p1.x),
            bottom = max(p0.y, p1.y)
        )
    }
}

data class PositionedPage(
    val descriptor: PageDescriptor,
    val worldBounds: DocRect
)

data class PreparedLayerGroup(
    val layer: LayerDescriptor,
    val objects: List<SceneObject>,
    val draftStrokes: List<DraftStrokeSceneItem>
)

data class PreparedScene(
    val scene: Scene,
    val scaleBucket: Int,
    val visibleWorldRect: DocRect,
    val pageClipUnionWorld: DocRect?,
    val visiblePages: List<PositionedPage>,
    val visibleTiles: List<TileArtifact>,
    val layerGroups: List<PreparedLayerGroup>,
    val totalSceneObjects: Int,
    val visibleObjectsCount: Int,
    val culledObjectsCount: Int
)

object ScenePreparer {

    fun computePageBoundsMap(pages: List<PageDescriptor>): Map<PageId, DocRect> {
        val map = LinkedHashMap<PageId, DocRect>(pages.size)
        var currentTop = 0.0
        for (page in pages) {
            val rect = DocRect(
                left = 0.0,
                top = currentTop,
                right = page.widthPt,
                bottom = currentTop + page.heightPt
            )
            map[page.id] = rect
            currentTop += page.heightPt
        }
        return map
    }

    fun prepare(
        scene: Scene,
        overrideViewportDocRect: DocRect? = null,
        overrideCamera: CameraTransform = scene.camera,
        objectIds: Set<ObjectId>? = null
    ): PreparedScene {
        val scaleBucket = ScaleBucket.compute(overrideCamera.scale)
        val visibleRect = overrideViewportDocRect ?: CameraRelativeProjector.visibleWorldRect(
            camera = overrideCamera,
            viewportWidthPx = scene.viewportWidthPx,
            viewportHeightPx = scene.viewportHeightPx
        )

        val spacePages = scene.snapshot.pages.filter { it.spaceId == scene.activeSpaceId }
        val pageBoundsMap = computePageBoundsMap(spacePages)
        val visiblePages = ArrayList<PositionedPage>(spacePages.size)
        var pageUnion: DocRect? = null
        for (p in spacePages) {
            val bounds = pageBoundsMap[p.id] ?: continue
            pageUnion = pageUnion?.union(bounds) ?: bounds
            if (bounds.intersects(visibleRect)) {
                visiblePages.add(PositionedPage(p, bounds))
            }
        }

        val clipUnion = if (scene.clipToPageBounds) pageUnion else null

        val totalObjects = scene.snapshot.totalObjectCount

        val visibleLayers = scene.snapshot.layers.filter { it.isVisible && it.opacity > 0.0f }
        val visibleLayerIds = HashSet<LayerId>(visibleLayers.size)
        for (l in visibleLayers) {
            visibleLayerIds.add(l.id)
        }

        val objectsByLayer = HashMap<LayerId, MutableList<SceneObject>>(visibleLayers.size)
        var visibleCount = 0

        val hiddenAttachments = if(objectIds != null) emptySet() else scene.snapshot.directory.allResidentObjects().flatMap { obj ->
            (obj.payload as? inkspace.core.payload.StickyPayload)?.takeIf { it.isCollapsed }?.attachedIds.orEmpty()
        }.toSet()
        val candidateIds = objectIds ?: scene.snapshot.queryBounds(visibleRect)
        for (id in candidateIds) {
            val obj = scene.snapshot.getObject(id) ?: continue
            if (id in hiddenAttachments) continue
            if (obj.space != scene.activeSpaceId) {
                continue
            }
            if (obj.layer !in visibleLayerIds || !obj.style.isVisible || obj.style.opacity <= 0.0f) {
                continue
            }
            if (clipUnion != null && !obj.bounds.intersects(clipUnion)) {
                continue
            }
            val list = objectsByLayer.getOrPut(obj.layer) { ArrayList() }
            list.add(obj)
            visibleCount += 1
        }
        val culledCount = (totalObjects - visibleCount).coerceAtLeast(0)

        val objectOrderComparator = compareBy<SceneObject> { it.style.zOrder }
            .thenBy { it.id.value }
        for (list in objectsByLayer.values) {
            list.sortWith(objectOrderComparator)
        }

        val draftsByLayer = HashMap<LayerId, MutableList<DraftStrokeSceneItem>>()
        for (draft in scene.frozenDraftStrokes) {
            if (draft.spaceId == scene.activeSpaceId &&
                draft.layerId in visibleLayerIds &&
                draft.style.isVisible &&
                draft.style.opacity > 0.0f &&
                draft.bounds.intersects(visibleRect)
            ) {
                draftsByLayer.getOrPut(draft.layerId) { ArrayList() }.add(draft)
            }
        }

        val layerGroups = ArrayList<PreparedLayerGroup>(visibleLayers.size)
        for (layer in visibleLayers) {
            val objs = objectsByLayer[layer.id] ?: emptyList()
            val drafts = draftsByLayer[layer.id] ?: emptyList()
            if (objs.isNotEmpty() || drafts.isNotEmpty()) {
                layerGroups.add(
                    PreparedLayerGroup(
                        layer = layer,
                        objects = objs,
                        draftStrokes = drafts
                    )
                )
            }
        }

        val visibleTiles = scene.frozenTiles.filter { it.docBounds.intersects(visibleRect) }

        return PreparedScene(
            scene = scene,
            scaleBucket = scaleBucket,
            visibleWorldRect = visibleRect,
            pageClipUnionWorld = clipUnion,
            visiblePages = visiblePages,
            visibleTiles = visibleTiles,
            layerGroups = layerGroups,
            totalSceneObjects = totalObjects,
            visibleObjectsCount = visibleCount,
            culledObjectsCount = culledCount
        )
    }
}
