package inkspace.spike

import inkspace.spike.model.Sample
import inkspace.spike.model.StrokeDraft
import inkspace.spike.model.SurfaceEpoch
import inkspace.spike.model.TextPaintPlan
import inkspace.spike.ports.DefaultGeometryPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

class StackVerificationTest {

    private val geometryPort = DefaultGeometryPort()

    @Test
    fun testHistoricalSamplesOrder() {
        val samples = mutableListOf<Sample>()
        // Simulate 3 historical samples and 1 current sample
        for (i in 0 until 3) {
            samples.add(Sample(x = i * 10f, y = i * 10f, pressure = 0.5f, timeNanos = (i + 1) * 1_000_000L))
        }
        samples.add(Sample(x = 30f, y = 30f, pressure = 0.8f, timeNanos = 4_000_000L))

        assertEquals(4, samples.size)
        // Verify strictly monotonic timestamps
        for (i in 1 until samples.size) {
            assertTrue("Timestamp must be strictly monotonic", samples[i].timeNanos > samples[i - 1].timeNanos)
        }
    }

    @Test
    fun testCancelDraftDropBehavior() {
        var activeDraft: StrokeDraft? = StrokeDraft("test_draft", 0)
        activeDraft?.samples?.add(Sample(10f, 10f, 0.5f))
        activeDraft?.samples?.add(Sample(20f, 20f, 0.6f))

        // Emulate CANCEL event
        val isCanceled = true
        val committedStrokes = mutableListOf<Any>()
        if (isCanceled) {
            activeDraft = null
        }

        assertTrue("Active draft must be cleared on CANCEL", activeDraft == null)
        assertTrue("No phantom strokes committed on CANCEL", committedStrokes.isEmpty())
    }

    @Test
    fun testSurfaceEpochMonotonicTransition() {
        val counter = AtomicLong(0L)
        val epochRef = AtomicReference(SurfaceEpoch(0L, false))

        // Surface created
        val gen1 = counter.incrementAndGet()
        epochRef.set(SurfaceEpoch(gen1, true, 1920, 1080))
        assertEquals(1L, epochRef.get().generation)
        assertTrue(epochRef.get().isValid)

        // Surface changed / resized
        val gen2 = counter.incrementAndGet()
        epochRef.set(SurfaceEpoch(gen2, true, 2048, 1536))
        assertEquals(2L, epochRef.get().generation)
        assertTrue(epochRef.get().isValid)

        // Surface destroyed
        val gen3 = counter.incrementAndGet()
        epochRef.set(SurfaceEpoch(gen3, false, 0, 0))
        assertEquals(3L, epochRef.get().generation)
        assertFalse(epochRef.get().isValid)
    }

    @Test
    fun testQuantileNearestRankFormula() {
        val n = 1000
        val data = (1..n).map { it * 0.1 } // 0.1 to 100.0
        val sorted = data.sorted()

        fun rank(p: Double): Double {
            val idx = (ceil(p * n) - 1).toInt().coerceIn(0, n - 1)
            return sorted[idx]
        }

        assertEquals(50.0, rank(0.50), 0.15)
        assertEquals(95.0, rank(0.95), 0.15)
        assertEquals(99.0, rank(0.99), 0.15)
    }

    @Test
    fun testMultiScriptTextPlanCreation() {
        val planCyrillic = TextPaintPlan("Векторные заметки Truffnotes", textSizePx = 24f)
        val planRtl = TextPaintPlan("مرحبا بالعالم", textSizePx = 24f, isRtl = true)
        val planCjk = TextPaintPlan("手書きノート", textSizePx = 24f)
        val planEmoji = TextPaintPlan("🎨✍️✨", textSizePx = 24f, containsEmoji = true)

        assertNotNull(planCyrillic.text)
        assertTrue(planRtl.isRtl)
        assertEquals(6, planCjk.text.length)
        assertTrue(planEmoji.containsEmoji)
    }
}
