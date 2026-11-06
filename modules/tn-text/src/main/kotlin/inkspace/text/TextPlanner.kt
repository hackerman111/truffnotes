package inkspace.text

import inkspace.core.error.FeatureError
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.mutation.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.TextPayload
import inkspace.core.payload.TextRun
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.StickyPayload
import inkspace.core.text.ParagraphStyle
import inkspace.core.read.DocumentRead
import inkspace.core.unit.DocRect

sealed interface TextAction {
    data class Create(val id: ObjectId, val payload: TextPayload, val bounds: DocRect) : TextAction
    data class Replace(val id: ObjectId, val payload: TextPayload) : TextAction
    data class ApplyRun(val id: ObjectId, val run: TextRun) : TextAction
    data class Paragraph(val id: ObjectId, val paragraph: ParagraphStyle) : TextAction
}

data class TextToolSettings(val defaultPayload: TextPayload = TextPayload(""), val pinned: Boolean = false)

object TextPlanner {
    fun textOf(payload: inkspace.core.payload.ObjectPayload?): TextPayload? = when (payload) {
        is TextPayload -> payload
        is ShapePayload -> payload.label ?: TextPayload("")
        is StickyPayload -> payload.richText ?: TextPayload(payload.content)
        else -> null
    }
    fun fullPage(read: DocumentRead, id: ObjectId, payload: TextPayload, ctx: PlanContext = PlanContext()): Outcome<EditPlan, FeatureError> {
        val page = read.getPage(ctx.activePageId) ?: return Outcome.Failure(FeatureError.EntityNotFound(ctx.activePageId.value))
        return plan(read, TextAction.Create(id, payload.copy(pageBound = true), DocRect(0.0, 0.0, page.widthPt, page.heightPt)), ctx)
    }
    fun plan(read: DocumentRead, action: TextAction, ctx: PlanContext = PlanContext()): Outcome<EditPlan, FeatureError> {
        if (action is TextAction.ApplyRun || action is TextAction.Paragraph) {
            val id = when (action) { is TextAction.ApplyRun -> action.id; is TextAction.Paragraph -> action.id; else -> error("unreachable") }
            val old = textOf(read.getObject(id)?.payload) ?: return Outcome.Failure(FeatureError.InvalidParameters("Not a text container"))
            val updated = when (action) {
                is TextAction.ApplyRun -> old.copy(runs = old.runs + action.run)
                is TextAction.Paragraph -> old.copy(paragraph = action.paragraph)
                else -> old
            }
            return plan(read, TextAction.Replace(id, updated), ctx)
        }
        val payload = when (action) { is TextAction.Create -> action.payload; is TextAction.Replace -> action.payload; else -> error("unreachable") }
        if (payload.text.length > 1_000_000 || !TextInputMap.isWellFormed(payload.text)) return Outcome.Failure(FeatureError.InvalidParameters("Text size limit"))
        val map = TextInputMap(payload.text)
        if (payload.runs.any {
            it.start > it.end || !map.isBoundary(it.start) || !map.isBoundary(it.end)
        }) return Outcome.Failure(FeatureError.InvalidParameters("Invalid text size or grapheme run boundary"))
        val preconditions = mutableListOf<Precondition>()
        val mutation = when (action) {
            is TextAction.Create -> {
                val layer = read.getLayer(ctx.activeLayerId)
                    ?: return Outcome.Failure(FeatureError.EntityNotFound(ctx.activeLayerId.value))
                if (layer.isLocked) return Outcome.Failure(FeatureError.LockedTarget(layer.id.value, "Layer locked"))
                if (!layer.isVisible) return Outcome.Failure(FeatureError.HiddenTarget(layer.id.value, "Layer hidden"))
                InsertObject(ctx.activeSpaceId, layer.id, action.id, payload.copy(containerBounds = payload.containerBounds ?: action.bounds), action.bounds)
            }
            is TextAction.Replace -> {
                val obj = read.getObject(action.id) ?: return Outcome.Failure(FeatureError.EntityNotFound(action.id.value))
                if (textOf(obj.payload) == null) return Outcome.Failure(FeatureError.InvalidParameters("Not a text container"))
                if (obj.isLocked || read.isLayerLocked(obj.layer)) return Outcome.Failure(FeatureError.LockedTarget(obj.id.value, "Text locked"))
                if (payload == obj.payload) return Outcome.Failure(FeatureError.NoOpAction("Text unchanged"))
                preconditions.add(Precondition(obj.id.value, obj.version))
                val updated = when (val original = obj.payload) {
                    is ShapePayload -> original.copy(label = payload)
                    is StickyPayload -> original.copy(richText = payload, content = payload.text)
                    else -> payload
                }
                UpdatePayload(obj.id, updated, obj.bounds)
            }
            else -> error("unreachable")
        }
        return Outcome.Success(EditPlan(read.version, preconditions, listOf(mutation), ctx.gestureId, UndoLabel("Text")))
    }
}
