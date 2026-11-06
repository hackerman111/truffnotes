package inkspace.text

import inkspace.core.id.ObjectId
import inkspace.core.payload.TextPayload
import kotlin.test.*
import org.junit.jupiter.api.Test

class F10TextTest {
    @Test fun emoji_surrogate() {
        val map = TextInputMap("a😀b")
        assertFalse(map.isBoundary(2))
        assertEquals(5, map.utf8Offset(3))
        assertEquals(3, map.utf16Offset(5))
    }
    @Test fun combining_grapheme() {
        val map = TextInputMap("e\u0301👩‍👩‍👦")
        assertFalse(map.isBoundary(1))
        assertFalse(map.isBoundary(5))
        assertTrue(map.isBoundary(2))
    }
    @Test fun cjk_composition_and_cancel() {
        val session = TextEditSession(ObjectId("a"), TextPayload("你好"), 1)
        assertTrue(session.replace(1, 0, 2, "に", true))
        assertTrue(session.replace(1, 0, 1, "日本", true))
        assertEquals("日本", session.payload.text)
        session.cancelComposition()
        assertEquals("你好", session.payload.text)
    }
    @Test fun stale_composition() {
        val session = TextEditSession(ObjectId("a"), TextPayload("a"), 1)
        session.blur()
        assertFalse(session.replace(1, 0, 1, "wrong", true))
        assertEquals("a", session.payload.text)
    }
    @Test fun selection_and_delete_cannot_split_grapheme() {
        val session = TextEditSession(ObjectId("a"), TextPayload("a😀e\u0301"), 1)
        assertFalse(session.select(2, 2))
        assertTrue(session.select(5, 5))
        assertTrue(session.deleteSurrounding(1, 1, 0))
        assertEquals("a😀", session.payload.text)
    }
    @Test fun rtl_mixed_commit_is_lossless() {
        val text = "Привет hello مرحبا 😀"
        val session = TextEditSession(ObjectId("a"), TextPayload(text, isRtl = true), 1)
        assertTrue(session.replace(1, 0, 0, "文", false))
        assertEquals("文$text", session.payload.text)
    }
}
