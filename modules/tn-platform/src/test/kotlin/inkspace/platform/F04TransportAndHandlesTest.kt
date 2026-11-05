package inkspace.platform

import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.id.UndoLabel
import inkspace.core.id.GestureId
import inkspace.core.limits.Limits
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentState
import inkspace.core.model.SessionKey
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.runtime.InputActionKind
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

class F04TransportAndHandlesTest {

    private val testSession = SessionKey(DocumentId("doc-f04"), SessionEpoch(1L))

    @Test
    fun stale_handle() {
        val registry = SessionHandleRegistry()
        val handleGen1 = registry.issueHandle(testSession, ownerContext = Any())
        assertEquals(1L, handleGen1.generation)
        assertTrue(registry.validate(handleGen1).isSuccess)

        // Issue a new generation handle for the same session, superseding gen 1
        val handleGen2 = registry.issueHandle(testSession, ownerContext = Any())
        assertEquals(2L, handleGen2.generation)

        val staleCheck = registry.validate(handleGen1)
        assertTrue(staleCheck.isFailure)
        val err = staleCheck.errorOrNull() as SubmitError.StaleHandle
        assertEquals(2L, err.expectedGeneration)
        assertEquals(1L, err.actualGeneration)

        // Gen 2 remains valid until closed
        assertTrue(registry.validate(handleGen2).isSuccess)
    }

    @Test
    fun double_close() {
        val registry = SessionHandleRegistry()
        val handle = registry.issueHandle(testSession, ownerContext = Any())

        val firstClose = registry.closeHandle(handle)
        assertTrue(firstClose.isSuccess)
        assertTrue(firstClose.getOrNull() == true)

        // Second close on the same handle must not throw and must report already closed (false)
        val secondClose = registry.closeHandle(handle)
        assertTrue(secondClose.isSuccess)
        assertEquals(false, secondClose.getOrNull())

        // Subsequent use returns StaleHandle
        assertTrue(registry.validate(handle).isFailure)

        // Also verify FdResourceHandle double close is idempotent and closes underlying FD once
        val closeCount = AtomicInteger(0)
        val fdHandle = FdResourceHandle(
            descriptorId = "fd-test-1",
            generation = 1L,
            closeable = Closeable { closeCount.incrementAndGet() }
        )
        assertTrue(fdHandle.useDescriptor { "ok" }.isSuccess)
        assertTrue(fdHandle.close())
        assertFalse(fdHandle.close())
        assertEquals(1, closeCount.get())
        assertTrue(fdHandle.useDescriptor { "after-close" }.isFailure)
    }

    @Test
    fun invalid_payload() {
        val transport = InputTransport(poolSize = 4, maxSamplesPerBatch = 3000, maxBatchBytes = 64 * 1024)

        // 1. Empty batch (0 samples) -> InvalidPayload
        val emptyBuf = transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 1L, action = InputActionKind.DOWN).getOrNull()!!
        val emptyHandoff = transport.handoff(emptyBuf)
        assertTrue(emptyHandoff.isFailure)
        assertTrue(emptyHandoff.errorOrNull() is SubmitError.InvalidPayload)

        // 2. Non-finite coordinate (Float.NaN) -> InvalidPayload
        val nanBuf = transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 2L, action = InputActionKind.MOVE).getOrNull()!!
        val nanAppend = nanBuf.appendSample(Float.NaN, 10f, 0.5f, 0f, 100L)
        assertTrue(nanAppend.isFailure)
        assertTrue(nanAppend.errorOrNull() is SubmitError.InvalidPayload)
        transport.cancelBuffer(nanBuf)

        // 3. Invalid pressure (> 1.0f) -> InvalidPayload
        val pressureBuf = transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 3L, action = InputActionKind.MOVE).getOrNull()!!
        val badPressure = pressureBuf.appendSample(10f, 20f, 1.5f, 0f, 100L)
        assertTrue(badPressure.isFailure)
        assertTrue(badPressure.errorOrNull() is SubmitError.InvalidPayload)
        transport.cancelBuffer(pressureBuf)

        // 4. Non-monotonic timestamp -> InvalidPayload
        val timeBuf = transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 4L, action = InputActionKind.MOVE).getOrNull()!!
        assertTrue(timeBuf.appendSample(10f, 20f, 0.5f, 0f, 200L).isSuccess)
        val backwardsTime = timeBuf.appendSample(12f, 22f, 0.5f, 0f, 150L)
        assertTrue(backwardsTime.isFailure)
        assertTrue(backwardsTime.errorOrNull() is SubmitError.InvalidPayload)
        transport.cancelBuffer(timeBuf)

        // 5. Batch exceeding 64 KiB -> ByteBudgetExceeded
        val hugeBuf = transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 5L, action = InputActionKind.MOVE).getOrNull()!!
        var overflowError: SubmitError? = null
        for (i in 0 until 2800) {
            val res = hugeBuf.appendSample(i.toFloat(), i.toFloat(), 0.5f, 0f, 1000L + i)
            if (res.isFailure) {
                overflowError = res.errorOrNull()
                break
            }
        }
        assertTrue(overflowError is SubmitError.ByteBudgetExceeded)
        transport.cancelBuffer(hugeBuf)
    }

    @Test
    fun mutate_after_handoff() {
        val transport = InputTransport(poolSize = 2)
        val buf = transport.acquireBuffer(testSession, pointerId = 1, sequenceNumber = 10L, action = InputActionKind.MOVE).getOrNull()!!
        assertTrue(buf.appendSample(100f, 200f, 0.6f, 0.1f, 1000L).isSuccess)
        assertTrue(buf.appendSample(110f, 210f, 0.7f, 0.2f, 1004L).isSuccess)

        val handoffResult = transport.handoff(buf)
        assertTrue(handoffResult.isSuccess)
        val leasedBatch = handoffResult.getOrNull()!!

        // Attempting to append or overwrite via the sender's buffer reference after handoff must fail with StaleHandle
        val postHandoffAppend = buf.appendSample(999f, 999f, 1.0f, 0f, 2000L)
        assertTrue(postHandoffAppend.isFailure)
        assertTrue(postHandoffAppend.errorOrNull() is SubmitError.StaleHandle)

        val postHandoffOverwrite = buf.overwriteSampleForTest(0, 999f, 999f)
        assertTrue(postHandoffOverwrite.isFailure)
        assertTrue(postHandoffOverwrite.errorOrNull() is SubmitError.StaleHandle)

        // Verify the handed-off ImmutableInputBatch was completely unaffected
        assertEquals(2, leasedBatch.batch.sampleCount)
        assertEquals(100f, leasedBatch.batch.sampleX(0))
        assertEquals(200f, leasedBatch.batch.sampleY(0))
        assertEquals(110f, leasedBatch.batch.sampleX(1))
        assertEquals(210f, leasedBatch.batch.sampleY(1))

        leasedBatch.close()
    }

    @Test
    fun pool_reuse_after_cancel() {
        // Pool with a single slot to force deterministic slot reuse
        val transport = InputTransport(poolSize = 1)

        val bufGen1 = transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 1L, action = InputActionKind.DOWN).getOrNull()!!
        assertEquals(0, bufGen1.slotIndex)
        val gen1 = bufGen1.generation
        assertTrue(bufGen1.appendSample(1f, 2f, 0.5f, 0f, 10L).isSuccess)

        // While slot 0 is in use, acquiring another buffer fails with QueueOverflow
        assertTrue(transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 2L, action = InputActionKind.MOVE).isFailure)

        // Cancel bufGen1 -> returns slot 0 to pool with bumped generation
        transport.cancelBuffer(bufGen1)

        // Re-acquire slot 0 -> gets new generation
        val bufGen2 = transport.acquireBuffer(testSession, pointerId = 0, sequenceNumber = 2L, action = InputActionKind.DOWN).getOrNull()!!
        assertEquals(0, bufGen2.slotIndex)
        assertTrue(bufGen2.generation > gen1)
        assertTrue(bufGen2.appendSample(50f, 60f, 0.8f, 0f, 20L).isSuccess)

        // Old reference bufGen1 cannot mutate or cancel bufGen2!
        assertTrue(bufGen1.appendSample(999f, 999f, 0.5f, 0f, 30L).isFailure)
        assertFalse(transport.cancelBuffer(bufGen1))

        val handoffGen2 = transport.handoff(bufGen2)
        assertTrue(handoffGen2.isSuccess)
        val batch2 = handoffGen2.getOrNull()!!
        assertEquals(1, batch2.batch.sampleCount)
        assertEquals(50f, batch2.batch.sampleX(0))
        assertEquals(60f, batch2.batch.sampleY(0))
        batch2.close()
    }

    @Test
    fun snapshot_alias() {
        val docId = DocumentId("doc-alias")
        val state = DocumentState.createInitial(docId)
        val mutableCallerSamples = mutableListOf(
            StrokeSamplePoint(0f, 0f, 0.5f),
            StrokeSamplePoint(10f, 10f, 0.7f)
        )
        val strokePayload = StrokePayload(
            brushId = "ball",
            color = 0xFF000000.toInt(),
            baseWidthPt = 2.0,
            origin = DocPoint(0.0, 0.0),
            samples = mutableCallerSamples
        )
        val plan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            preconditions = emptyList(),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("stroke-1"),
                    payload = strokePayload,
                    bounds = DocRect(0.0, 0.0, 10.0, 10.0)
                )
            ),
            gesture = GestureId("g-1"),
            label = UndoLabel("draw")
        )
        val validated = validatePlan(state, plan, Limits()).getOrNull()!!
        applyPlan(state, validated)

        val publishedSnapshot = state.snapshot()
        val initialFingerprint = publishedSnapshot.contentFingerprint()

        // 1. Mutate the caller's original mutable list after handoff/apply
        mutableCallerSamples.add(StrokeSamplePoint(999f, 999f, 1.0f))
        mutableCallerSamples[0] = StrokeSamplePoint(-500f, -500f, 0.1f)

        val snapStroke = publishedSnapshot.getObject(ObjectId("stroke-1"))!!.payload as StrokePayload
        assertEquals(2, snapStroke.samples.size)
        assertEquals(0f, snapStroke.samples[0].relX)
        assertEquals(initialFingerprint, publishedSnapshot.contentFingerprint())

        // 2. Attempting to cast snapshot collections to MutableList and mutate them throws UnsupportedOperationException
        assertThrows(UnsupportedOperationException::class.java) {
            (publishedSnapshot.pages as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (publishedSnapshot.layers as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (publishedSnapshot.orderedSceneObjects() as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapStroke.samples as MutableList).clear()
        }

        // 3. Verify SessionHandle does not retain Activity reference after detach/GC
        val registry = SessionHandleRegistry()
        var simulatedActivity: Any? = object : Any() {}
        val weakActivity = WeakReference(simulatedActivity)
        val handle = registry.issueHandle(testSession, ownerContext = simulatedActivity)
        registry.detachActivityContext(handle)
        simulatedActivity = null
        System.gc()
        Thread.sleep(20)
        System.gc()
        assertNull(weakActivity.get(), "SessionHandle must not retain Activity reference after detach")
    }
}
