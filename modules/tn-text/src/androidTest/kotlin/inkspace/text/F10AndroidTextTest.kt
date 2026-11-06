package inkspace.text

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.inputmethod.EditorInfo
import inkspace.core.id.ObjectId
import inkspace.core.payload.TextPayload
import inkspace.core.payload.TextRun
import inkspace.core.text.ParagraphStyle
import inkspace.core.text.TextAlignment
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class F10AndroidTextTest {
    @Test fun shared_layout_fallback_rtl_emoji_and_raster_budget() {
        val port = AndroidTextPort()
        val plan = port.paintPlan(TextPayload("Привет 😀 مرحبا 文", fontFamily = "missing-font-123",
            runs = listOf(TextRun(0, 6, bold = true, strike = true, highlight = -256)),
            paragraph = ParagraphStyle(TextAlignment.CENTER)), 240.0)
        assertTrue(plan.layout.lineCount > 0)
        val cursor = plan.cursorRect(0)
        assertTrue(cursor.height > 0)
        val raster = plan.rasterize(1.0, 100000)
        assertEquals(240, raster.width)
        assertTrue(raster.height > 0)
        try { plan.rasterize(100.0, 10); fail("budget accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(port.cachedBytes > 0)
        port.clear(); assertEquals(0L, port.cachedBytes)
    }
    @Test fun ime_composition_batch_and_stale_focus_have_one_commit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TextInputView(instrumentation.targetContext)
            var commits = 0
            var text = ""
            view.onCommit = { commits++; text = it.text }
            view.bind(TextEditSession(ObjectId("a"), TextPayload(""), 1))
            val first = view.onCreateInputConnection(EditorInfo())!!
            assertTrue(first.beginBatchEdit())
            assertTrue(first.setComposingText("に", 1))
            assertTrue(first.setComposingText("日本", 1))
            assertEquals(0, commits)
            assertTrue(first.finishComposingText())
            assertTrue(first.endBatchEdit())
            assertEquals(1, commits); assertEquals("日本", text)
            view.bind(TextEditSession(ObjectId("b"), TextPayload("😀"), 2))
            assertFalse(first.commitText("stale", 1))
            val second = view.onCreateInputConnection(EditorInfo())!!
            assertTrue(second.deleteSurroundingTextInCodePoints(1, 0))
            assertEquals("", text)
            view.unbind()
        }
    }
}
