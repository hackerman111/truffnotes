package inkspace.content

import inkspace.core.error.FeatureError
import inkspace.core.id.*
import inkspace.core.model.SceneObject
import inkspace.core.mutation.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.*
import inkspace.core.read.DocumentRead
import inkspace.core.unit.DocRect
import inkspace.core.unit.DocPoint

sealed interface ContentAction {
    data class InsertImage(val id: ObjectId, val asset: AssetId, val width: Int, val height: Int, val bounds: DocRect, val exifOrientation: Int = 1) : ContentAction
    data class CropImage(val id: ObjectId, val crop: DocRect) : ContentAction
    data class InsertSticky(val id: ObjectId, val color: Int, val text: String, val bounds: DocRect) : ContentAction
    data class CollapseSticky(val id: ObjectId, val collapsed: Boolean) : ContentAction
    data class InsertTape(val id: ObjectId, val color: Int, val bounds: DocRect) : ContentAction
    data class RemoveTape(val ids: Set<ObjectId>) : ContentAction
    data class CropMask(val id: ObjectId, val points: List<DocPoint>) : ContentAction
    data class AttachSticky(val id: ObjectId, val childIds: Set<ObjectId>) : ContentAction
    data class DetachSticky(val id: ObjectId, val childIds: Set<ObjectId>) : ContentAction
    data class StickyAppearance(val id: ObjectId, val color: Int) : ContentAction
    data class DrawTape(val id: ObjectId, val points: List<DocPoint>, val color: Int, val widthPt: Double = 24.0,
        val pattern: String = "solid", val straight: Boolean = false) : ContentAction
    data class InsertBundle(val bundle: ObjectBundle, val idPrefix: String, val deltaX: Double = 0.0, val deltaY: Double = 0.0) : ContentAction
}

object ContentPlanner {
    fun plan(read: DocumentRead, action: ContentAction, ctx: PlanContext = PlanContext()): Outcome<EditPlan, FeatureError> {
        val preconditions = ArrayList<Precondition>()
        fun editable(id: ObjectId): SceneObject? {
            val obj = read.getObject(id) ?: return null
            if (obj.isLocked || read.isLayerLocked(obj.layer) || read.getLayer(obj.layer)?.isVisible == false) return null
            preconditions.add(Precondition(id.value, obj.version))
            return obj
        }
        fun insert(id: ObjectId, payload: ObjectPayload, bounds: DocRect): Mutation =
            InsertObject(ctx.activeSpaceId, ctx.activeLayerId, id, payload, bounds)
        val mutations: List<Mutation> = when (action) {
            is ContentAction.InsertImage -> {
                if (action.width <= 0 || action.height <= 0 || action.width.toLong() * action.height > 100_000_000L)
                    return invalid("Image exceeds dimensions limit")
                if (action.exifOrientation !in 1..8) return invalid("Invalid image orientation")
                listOf(insert(action.id, ImagePayload(action.asset, action.width, action.height, exifOrientation = action.exifOrientation, containerBounds = action.bounds), action.bounds))
            }
            is ContentAction.InsertSticky -> {
                if (action.text.length > 1_000_000) return invalid("Sticky text limit")
                listOf(insert(action.id, StickyPayload(action.color, action.text), action.bounds))
            }
            is ContentAction.InsertTape -> listOf(insert(action.id, TapePayload(action.color), action.bounds))
            is ContentAction.CropImage -> {
                val obj = editable(action.id) ?: return invalid("Image missing, hidden or locked")
                val image = obj.payload as? ImagePayload ?: return invalid("Not an image")
                val crop = action.crop
                if (crop.left < 0 || crop.top < 0 || crop.right > image.naturalWidthPx || crop.bottom > image.naturalHeightPx || crop.width <= 0 || crop.height <= 0)
                    return invalid("Crop outside immutable source")
                listOf(UpdatePayload(obj.id, image.copy(cropRect = crop), obj.bounds))
            }
            is ContentAction.CollapseSticky -> {
                val obj = editable(action.id) ?: return invalid("Sticky missing, hidden or locked")
                val sticky = obj.payload as? StickyPayload ?: return invalid("Not a Sticky")
                listOf(UpdatePayload(obj.id, sticky.copy(isCollapsed = action.collapsed), obj.bounds))
            }
            is ContentAction.CropMask -> {
                val obj = editable(action.id) ?: return invalid("Image missing, hidden or locked")
                val payload = obj.payload as? ImagePayload ?: return invalid("Not an image")
                if (action.points.size !in 3..4096 || action.points.any { it.x !in 0.0..payload.naturalWidthPx.toDouble() || it.y !in 0.0..payload.naturalHeightPx.toDouble() }) return invalid("Invalid freehand crop mask")
                listOf(UpdatePayload(obj.id, payload.copy(cropMask = java.util.List.copyOf(action.points)), obj.bounds))
            }
            is ContentAction.AttachSticky -> {
                val obj = editable(action.id) ?: return invalid("Sticky missing, hidden or locked")
                val payload = obj.payload as? StickyPayload ?: return invalid("Not a Sticky")
                if (action.childIds.isEmpty() || action.childIds.size + payload.attachedIds.size > 1024) return invalid("Sticky attachment limit")
                for (id in action.childIds) {
                    val child = editable(id) ?: return invalid("Child missing, hidden or locked")
                    if (child.space != obj.space || child.layer != obj.layer || child.payload !is StrokePayload && child.payload !is ShapePayload && child.payload !is ImagePayload) return invalid("Unsupported Sticky child")
                }
                listOf(UpdatePayload(obj.id, payload.copy(attachedIds = java.util.List.copyOf((payload.attachedIds + action.childIds).distinct())), obj.bounds))
            }
            is ContentAction.DetachSticky -> {
                val obj = editable(action.id) ?: return invalid("Sticky missing, hidden or locked")
                val payload = obj.payload as? StickyPayload ?: return invalid("Not a Sticky")
                listOf(UpdatePayload(obj.id, payload.copy(attachedIds = payload.attachedIds.filterNot { it in action.childIds }), obj.bounds))
            }
            is ContentAction.StickyAppearance -> {
                val obj = editable(action.id) ?: return invalid("Sticky missing, hidden or locked")
                val payload = obj.payload as? StickyPayload ?: return invalid("Not a Sticky")
                listOf(UpdatePayload(obj.id, payload.copy(color = action.color), obj.bounds))
            }
            is ContentAction.DrawTape -> {
                if (action.points.size !in 2..65536 || !action.widthPt.isFinite() || action.widthPt !in 0.1..1000.0 || action.pattern !in setOf("solid", "striped", "dotted")) return invalid("Invalid Tape geometry")
                val points = if (action.straight) listOf(action.points.first(), action.points.last()) else java.util.List.copyOf(action.points)
                val radius = action.widthPt / 2
                val bounds = DocRect(points.minOf { it.x } - radius, points.minOf { it.y } - radius,
                    points.maxOf { it.x } + radius, points.maxOf { it.y } + radius)
                listOf(insert(action.id, TapePayload(action.color, points = points, widthPt = action.widthPt, pattern = action.pattern, straight = action.straight), bounds))
            }
            is ContentAction.InsertBundle -> {
                if (!action.deltaX.isFinite() || !action.deltaY.isFinite()) return invalid("Invalid bundle translation")
                val bundle = action.bundle.remap(action.idPrefix)
                if (bundle.objects.any { read.objectState(it.id) != inkspace.core.read.ResidencyState.Missing }) return invalid("Bundle ID collision")
                val ordered = bundle.inInsertionOrder()
                ordered.map { obj ->
                    val delta = inkspace.core.model.AffineTransform2D(1.0, 0.0, action.deltaX, 0.0, 1.0, action.deltaY)
                    InsertObject(ctx.activeSpaceId, ctx.activeLayerId, obj.id, obj.payload,
                        DocRect(obj.bounds.left + action.deltaX, obj.bounds.top + action.deltaY, obj.bounds.right + action.deltaX, obj.bounds.bottom + action.deltaY),
                        delta.multiply(obj.transform), obj.style, obj.isLocked)
                }
            }
            is ContentAction.RemoveTape -> {
                if (action.ids.isEmpty()) return invalid("No Tape selected")
                val targets = action.ids.map { editable(it) ?: return invalid("Tape missing, hidden or locked") }
                if (targets.any { it.payload !is TapePayload }) return invalid("Remove Tape target is not Tape")
                targets.map { RemoveObject(it.id) }
            }
        }
        if (mutations.any { it is InsertObject }) {
            val layer = read.getLayer(ctx.activeLayerId) ?: return invalid("Missing active layer")
            if (layer.isLocked || !layer.isVisible) return invalid("Active layer hidden or locked")
        }
        return Outcome.Success(EditPlan(read.version, preconditions, mutations, ctx.gestureId, UndoLabel("Content")))
    }
    private fun invalid(reason: String): Outcome.Failure<FeatureError> = Outcome.Failure(FeatureError.InvalidParameters(reason))
}
