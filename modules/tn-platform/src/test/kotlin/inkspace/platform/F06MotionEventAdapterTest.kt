package inkspace.platform

import android.view.MotionEvent
import inkspace.core.id.DocumentId
import inkspace.core.id.SessionEpoch
import inkspace.core.ink.InputToolType
import inkspace.core.ink.PredictedSample
import inkspace.core.model.SessionKey
import inkspace.core.runtime.InputActionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class F06MotionEventAdapterTest {

    private val session = SessionKey(DocumentId("doc-f06-platform"), SessionEpoch(1L))

    @Test
    fun motion_event_adapter_maps_all_actions_buttons_flags_and_honest_axis_capabilities() {
        val transport = InputTransport(poolSize = 8)

        // 1. Active stylus DOWN + historical MOVE + temporary eraser button + predicted samples
        val leasedStylus = transport.extractInkBatchFromAndroidEventData(
            session = session,
            pointerId = 1,
            actionMasked = MotionEvent.ACTION_MOVE,
            flags = 0,
            buttonState = MotionEvent.BUTTON_STYLUS_SECONDARY,
            androidToolType = MotionEvent.TOOL_TYPE_STYLUS,
            axisCapabilities = DeviceAxisCapabilities(supportsPressure = true, supportsTilt = true),
            sampleCount = 3,
            xs = floatArrayOf(10f, 15f, 20f),
            ys = floatArrayOf(20f, 25f, 30f),
            pressures = floatArrayOf(0.3f, 0.5f, 0.7f),
            tilts = floatArrayOf(0.1f, 0.2f, 0.3f),
            timestampsMs = longArrayOf(100L, 104L, 108L),
            predictedSamples = listOf(PredictedSample(25f, 35f, 0.75f, 0.3f, 112L))
        ).getOrNull()!!

        leasedStylus.use { wrapper ->
            val batch = wrapper.inkBatch
            assertEquals(InputActionKind.MOVE, batch.action)
            assertEquals(InputToolType.STYLUS, batch.toolType)
            assertTrue(batch.supportsPressure)
            assertTrue(batch.supportsTilt)
            assertFalse(batch.capabilityReport.isPassiveTouch)
            assertTrue(batch.secondaryEraserButtonPressed)
            assertFalse(batch.isFlagCanceled)
            assertEquals(3, batch.realSampleCount)
            assertEquals(1, batch.predictedSampleCount)
            assertEquals(0.7f, batch.realPressure(2), 1e-4f)
            assertEquals(0.3f, batch.realTilt(2), 1e-4f)
        }
        assertTrue(leasedStylus.isClosed())

        // 2. Passive capacitive stylus / finger touch: honestly classified as FINGER without fake pressure/tilt
        val leasedPassive = transport.extractInkBatchFromAndroidEventData(
            session = session,
            pointerId = 0,
            actionMasked = MotionEvent.ACTION_DOWN,
            flags = 0,
            buttonState = 0,
            androidToolType = MotionEvent.TOOL_TYPE_FINGER,
            axisCapabilities = null, // defaults from toolType=FINGER
            sampleCount = 1,
            xs = floatArrayOf(50f),
            ys = floatArrayOf(60f),
            pressures = floatArrayOf(0.95f),
            tilts = floatArrayOf(0.45f),
            timestampsMs = longArrayOf(200L)
        ).getOrNull()!!

        leasedPassive.use { wrapper ->
            val batch = wrapper.inkBatch
            assertEquals(InputToolType.FINGER, batch.toolType)
            assertFalse(batch.supportsPressure)
            assertFalse(batch.supportsTilt)
            assertTrue(batch.capabilityReport.isPassiveTouch)
            // Missing pressure/tilt axes must report neutral defaults, never synthetic readings
            assertEquals(0.5f, batch.realPressure(0), 1e-4f)
            assertEquals(0.0f, batch.realTilt(0), 1e-4f)
        }

        // 3. Per-pointer FLAG_CANCELED on ACTION_POINTER_UP vs global ACTION_CANCEL
        val leasedFlagCanceled = transport.extractInkBatchFromAndroidEventData(
            session = session,
            pointerId = 2,
            actionMasked = MotionEvent.ACTION_POINTER_UP,
            flags = MotionEvent.FLAG_CANCELED,
            buttonState = 0,
            androidToolType = MotionEvent.TOOL_TYPE_FINGER,
            sampleCount = 1,
            xs = floatArrayOf(80f),
            ys = floatArrayOf(90f),
            pressures = floatArrayOf(0.5f),
            tilts = floatArrayOf(0f),
            timestampsMs = longArrayOf(300L)
        ).getOrNull()!!

        leasedFlagCanceled.use { wrapper ->
            val batch = wrapper.inkBatch
            assertEquals(InputActionKind.UP, batch.action)
            assertTrue(batch.isFlagCanceled)
            assertTrue(batch.isEffectiveCancel)
        }

        // 4. HOVER action
        val leasedHover = transport.extractInkBatchFromAndroidEventData(
            session = session,
            pointerId = 1,
            actionMasked = MotionEvent.ACTION_HOVER_MOVE,
            flags = 0,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            androidToolType = MotionEvent.TOOL_TYPE_STYLUS,
            sampleCount = 1,
            xs = floatArrayOf(120f),
            ys = floatArrayOf(140f),
            pressures = floatArrayOf(0f),
            tilts = floatArrayOf(0.25f),
            timestampsMs = longArrayOf(400L)
        ).getOrNull()!!

        leasedHover.use { wrapper ->
            val batch = wrapper.inkBatch
            assertEquals(InputActionKind.HOVER, batch.action)
            assertTrue(batch.primaryButtonPressed)
        }
    }
}
