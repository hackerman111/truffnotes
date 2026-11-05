package inkspace.ui

import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.SpaceId
import inkspace.core.id.UndoLabel
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.DocumentState
import inkspace.core.model.SessionKey
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.outcome.Outcome
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.ports.RequestId
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.AppGateway
import inkspace.core.runtime.EventBatch
import inkspace.core.runtime.ImmutableInputBatch
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.SaveStatus
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.runtime.ViewportState
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class F04BaselineUiControllerTest {

    @Test
    fun baseline_controller_handles_save_states_pending_overflow_and_callback_profiling() {
        val docId = DocumentId("doc-ui-test")
        val session = SessionKey(docId, SessionEpoch(1L))
        val initialSnap = DocumentState.createInitial(docId).snapshot()
        val fakeGateway = FakeUiGateway(session, initialSnap)

        val controller = BaselineSessionController(fakeGateway)
        assertTrue(controller.openDocument(docId).isSuccess)
        controller.syncEvents()

        assertEquals(SaveStatusKind.SAVED, controller.uiState.saveStatus?.status)
        assertFalse(controller.uiState.isInputBlockedByOverflow)

        // Transition to SAVING -> progress state visible, input still allowed
        fakeGateway.emitSaveStatus(
            SaveStatus(
                session = session,
                status = SaveStatusKind.SAVING,
                appliedRevision = Revision(2L),
                durableRevision = Revision(1L),
                pendingCount = 1,
                pendingBytes = 1024L
            )
        )
        controller.syncEvents()
        assertEquals(SaveStatusKind.SAVING, controller.uiState.saveStatus?.status)
        assertFalse(controller.uiState.isInputBlockedByOverflow)

        // Transition to PENDING_OVERFLOW -> input must be rejected immediately without blocking UI thread
        fakeGateway.emitSaveStatus(
            SaveStatus(
                session = session,
                status = SaveStatusKind.PENDING_OVERFLOW,
                appliedRevision = Revision(257L),
                durableRevision = Revision(1L),
                pendingCount = 256,
                pendingBytes = 8L * 1024L * 1024L,
                lastError = "Pending persistence queue full"
            )
        )
        controller.syncEvents()
        assertTrue(controller.uiState.isInputBlockedByOverflow)

        val batch = ImmutableInputBatch.copyFromArrays(
            session = session,
            pointerId = 0,
            sequenceNumber = 1L,
            action = InputActionKind.DOWN,
            count = 1,
            xs = floatArrayOf(10f),
            ys = floatArrayOf(20f),
            pressures = floatArrayOf(0.5f),
            tilts = floatArrayOf(0f),
            timestampsMs = longArrayOf(100L)
        ).getOrNull()!!

        val rejectedInput = controller.dispatchInputBatch(batch)
        assertTrue(rejectedInput.isFailure)
        assertTrue(rejectedInput.errorOrNull() is SubmitError.PendingPersistenceOverflow)

        // Drain back to SAVED -> input accepted again, profile 200 callbacks and verify p95 <= 1.0 ms
        fakeGateway.emitSaveStatus(
            SaveStatus(
                session = session,
                status = SaveStatusKind.SAVED,
                appliedRevision = Revision(257L),
                durableRevision = Revision(257L),
                pendingCount = 0,
                pendingBytes = 0L
            )
        )
        controller.syncEvents()
        assertFalse(controller.uiState.isInputBlockedByOverflow)

        controller.callbackTracker.reset()
        for (i in 1..200) {
            val b = ImmutableInputBatch.copyFromArrays(
                session = session,
                pointerId = 0,
                sequenceNumber = i.toLong(),
                action = InputActionKind.MOVE,
                count = 4,
                xs = floatArrayOf(1f, 2f, 3f, 4f),
                ys = floatArrayOf(1f, 2f, 3f, 4f),
                pressures = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f),
                tilts = floatArrayOf(0f, 0f, 0f, 0f),
                timestampsMs = longArrayOf(100L, 101L, 102L, 103L)
            ).getOrNull()!!
            assertTrue(controller.dispatchInputBatch(b).isSuccess)
        }

        val stats = controller.callbackTracker.computeQuantilesMs()
        assertEquals(200, stats.sampleCount)
        assertTrue(stats.p95Ms <= 1.0, "Main callback p95 (${stats.p95Ms} ms) must be <= 1.0 ms")
    }

    private class FakeUiGateway(
        private val session: SessionKey,
        private val snapshot: DocumentSnapshot
    ) : AppGateway {
        private val seq = AtomicLong(1L)
        private val events = ArrayDeque<AppEvent>()
        private val errors = ArrayDeque<AppEvent.ErrorEvent>()
        private var currentSave = SaveStatus(
            session = session,
            status = SaveStatusKind.SAVED,
            appliedRevision = Revision(0L),
            durableRevision = Revision(0L),
            pendingCount = 0,
            pendingBytes = 0L
        )
        private var currentViewport = ViewportState(session = session)

        override fun trySend(command: AppCommand): Outcome<RequestId, SubmitError> {
            when (command) {
                is AppCommand.OpenSession -> {
                    events.addLast(
                        AppEvent.SessionOpened(
                            session = session,
                            snapshot = snapshot,
                            saveStatus = currentSave,
                            viewport = currentViewport
                        )
                    )
                }
                is AppCommand.UpdateViewport -> {
                    currentViewport = command.state
                    events.addLast(AppEvent.ViewportChanged(currentViewport))
                }
                else -> {}
            }
            return Outcome.Success(RequestId(seq.getAndIncrement()))
        }

        fun emitSaveStatus(status: SaveStatus) {
            currentSave = status
            events.addLast(AppEvent.SaveStatusChanged(status))
        }

        override fun pollEvents(max: Int): EventBatch {
            val out = mutableListOf<AppEvent>()
            while (events.isNotEmpty() && out.size < max) {
                out.add(events.removeFirst())
            }
            val errs = errors.toList()
            return EventBatch(events = out, errorEvents = errs)
        }

        override fun closeAsync(): Outcome<RequestId, SubmitError> {
            events.addLast(AppEvent.Closed(0))
            return Outcome.Success(RequestId(seq.getAndIncrement()))
        }

        override fun latestSnapshot(session: SessionKey): DocumentSnapshot = snapshot
        override fun latestSaveStatus(session: SessionKey): SaveStatus = currentSave
        override fun latestViewport(session: SessionKey): ViewportState = currentViewport
        override fun acknowledgeError(error: AppEvent.ErrorEvent) {
            errors.remove(error)
        }
    }
}
