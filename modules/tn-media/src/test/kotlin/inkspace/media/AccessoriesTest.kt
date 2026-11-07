package inkspace.media

import kotlin.test.*
import org.junit.jupiter.api.Test

class AccessoriesTest {
    @Test fun laser_ephemeral() {
        var now = 0L
        val laser = LaserOverlay { now }
        laser.add(2.0, 3.0, true)
        laser.add(4.0, 5.0, true)
        assertEquals(2, laser.visible().size)
        now = 2_000_000_000L
        assertTrue(laser.visible().isEmpty())
        laser.add(6.0, 7.0, false)
        laser.add(8.0, 9.0, false)
        assertEquals(1, laser.visible().size)
    }
    @Test fun timer_background() {
        var now = 0L
        val timer = TimerAccessory(clock = { now })
        timer.start(); timer.start()
        now = 5_000_000_000L
        val restored = TimerAccessory(timer.snapshot(), { now })
        now = 8_000_000_000L
        assertEquals(8_000_000_000L, restored.elapsed())
        assertEquals(8_000_000_000L, restored.lap())
        restored.pause()
        now = 20_000_000_000L
        assertEquals(8_000_000_000L, restored.elapsed())
        restored.reset()
        assertEquals(0L, restored.elapsed())
        assertTrue(restored.snapshot().laps.isEmpty())
        val history = restored.snapshot().history.single()
        assertEquals(8_000_000_000L, history.elapsedNanos)
        assertEquals(listOf(8_000_000_000L), history.laps)
        val restarted = TimerAccessory(restored.snapshot(), { now })
        restarted.start()
        now += 2_000_000_000L
        restarted.reset()
        assertEquals(listOf(8_000_000_000L, 2_000_000_000L), restarted.snapshot().history.map { it.elapsedNanos })
    }
}
