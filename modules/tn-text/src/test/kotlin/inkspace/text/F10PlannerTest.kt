package inkspace.text

import inkspace.core.id.*
import inkspace.core.model.DocumentState
import inkspace.core.mutation.*
import inkspace.core.payload.*
import inkspace.core.unit.DocRect
import kotlin.test.*
import org.junit.jupiter.api.Test

class F10PlannerTest {
    @Test fun unicode_payload_codec_and_history_roundtrip() {
        val state = DocumentState.createInitial(DocumentId("text"))
        val payload = TextPayload("Привет 😀 مرحبا 文", runs = listOf(TextRun(0, 6, bold = true)))
        val plan = TextPlanner.plan(state, TextAction.Create(ObjectId("t"), payload, DocRect(0.0, 0.0, 300.0, 50.0))).getOrNull()!!
        val applied = applyPlan(state, validatePlan(state, plan, inkspace.core.limits.Limits()).getOrNull()!!).getOrNull()!!
        assertEquals(payload.copy(containerBounds = DocRect(0.0, 0.0, 300.0, 50.0)), state.getObjectPayload(ObjectId("t")))
        applyPlan(state, validatePlan(state, applied.inversePlan, inkspace.core.limits.Limits()).getOrNull()!!)
        assertNull(state.getObject(ObjectId("t")))
    }
    @Test fun style_range_cannot_split_emoji() {
        val state = DocumentState.createInitial(DocumentId("text"))
        val result = TextPlanner.plan(state, TextAction.Create(ObjectId("t"), TextPayload("😀", runs = listOf(TextRun(0, 1, bold = true))), DocRect(0.0, 0.0, 10.0, 10.0)))
        assertTrue(result.isFailure)
    }
}
