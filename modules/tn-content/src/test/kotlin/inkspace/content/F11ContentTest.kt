package inkspace.content

import inkspace.core.id.ObjectId
import kotlin.test.*
import org.junit.jupiter.api.Test

class F11ContentTest {
    @Test fun decode_bomb() {
        assertFailsWith<IllegalArgumentException> { ImageDecodePlan.create(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1) }
        val plan = ImageDecodePlan.create(10000, 10000, 400, 400)
        assertTrue(plan.decodedBytes <= 32L * 1024 * 1024)
        assertTrue(plan.sampleSize > 1)
    }
    @Test fun image_orientation() {
        val plan = ImageDecodePlan.create(800, 600, 400, 400, 6)
        assertEquals(90, plan.rotationDegrees)
        assertEquals(600, plan.orientedWidth)
        assertEquals(800, plan.orientedHeight)
    }
    @Test fun gif_budget() {
        val gif = GifPlaybackBudget(2, 1024)
        assertTrue(gif.admit(512))
        assertTrue(gif.admit(512))
        assertFalse(gif.admit(1))
        assertFalse(gif.shouldAnimate(false))
    }
    @Test fun tape_reveal_readonly() {
        val view = TapeRevealState()
        val tape = ObjectId("tape")
        view.toggle(tape)
        assertTrue(view.isRevealed(tape))
        assertFalse(TapeRevealState().isRevealed(tape))
        view.toggle(tape)
        assertFalse(view.isRevealed(tape))
    }
}
