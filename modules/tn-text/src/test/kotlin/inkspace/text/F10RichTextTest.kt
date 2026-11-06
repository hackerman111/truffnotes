package inkspace.text

import inkspace.core.id.*
import inkspace.core.model.DocumentState
import inkspace.core.payload.*
import inkspace.core.text.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class F10RichTextTest {
    @Test fun rich_styles_and_full_page_share_payload() {
        val payload = TextPayload("hello", runs = listOf(TextRun(0, 5, strike = true, highlight = 42,
            fontFamily = "serif", fontSizePt = 24.0)), paragraph = ParagraphStyle(TextAlignment.CENTER,
            list = TextListKind.NUMBERED), pageBound = true)
        val state = DocumentState.createInitial(DocumentId("text"))
        val plan = TextPlanner.fullPage(state, ObjectId("full"), payload).getOrNull()!!
        val insert = plan.mutations.single() as inkspace.core.mutation.InsertObject
        assertTrue((insert.payload as TextPayload).pageBound)
        assertEquals(595.28, insert.bounds.width)
    }
    @Test fun ime_cursor_and_codepoint_delete() {
        val session = TextEditSession(ObjectId("a"), TextPayload("😀word"), 3)
        assertTrue(session.replace(3, 2, 6, "abc", false, 0))
        assertEquals(2, session.selectionStart)
        assertTrue(session.deleteSurroundingCodePoints(3, 1, 0))
        assertEquals("abc", session.payload.text)
    }
    @Test fun composing_region_finish_cancel_and_stale_batch() {
        val session = TextEditSession(ObjectId("a"), TextPayload("abcd"), 3)
        assertTrue(session.setComposingRegion(3, 1, 3))
        assertTrue(session.replace(3, 1, 3, "日", true))
        session.cancelComposition()
        assertEquals("abcd", session.payload.text)
        assertFalse(session.apply(ImeEdit.Replace(ObjectId("wrong"), 3, 0, 1, "x")))
    }
    @Test fun malformed_unicode_rejected_without_loss() {
        val session = TextEditSession(ObjectId("a"), TextPayload("ok"), 1)
        assertFalse(session.replace(1, 0, 0, "\uD800", false))
        assertEquals("ok", session.payload.text)
    }
    @Test fun styles_survive_replace_and_inherit_at_caret() {
        val session = TextEditSession(ObjectId("a"), TextPayload("hello", runs = listOf(TextRun(0, 5, bold = true))), 1)
        assertTrue(session.replace(1, 2, 2, "!", false))
        assertTrue(session.payload.runs.any { it.start <= 2 && it.end >= 3 && it.bold })
    }
}
