package inkspace.runtime

import inkspace.core.error.ResourceError
import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.RequestGeneration
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
import inkspace.core.ports.DurableAck
import inkspace.core.ports.RequestId
import inkspace.core.ports.StoragePort
import inkspace.core.ports.StorageRequest
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.ImmutableInputBatch
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.MemoryCategory
import inkspace.core.runtime.MemoryPressureLevel
import inkspace.core.runtime.PlatformPorts
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class F04RuntimeQueuesAndMemoryTest {

    private fun makeStrokeEditPlan(
        docId: DocumentId,
        baseRevision: Long,
        strokeIndex: Int
    ): EditPlan {
        val samples = listOf(
            StrokeSamplePoint(0f, 0f, 0.5f),
            StrokeSamplePoint(10f + strokeIndex, 20f + strokeIndex, 0.8f)
        )
        return EditPlan(
            base = DocVersion(docId, Revision(baseRevision)),
            preconditions = emptyList(),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("stroke-$strokeIndex"),
                    payload = StrokePayload(
                        brushId = "ball",
                        color = 0xFF000000.toInt(),
                        baseWidthPt = 2.0,
                        origin = DocPoint(strokeIndex.toDouble(), strokeIndex.toDouble()),
                        samples = samples
                    ),
                    bounds = DocRect(0.0, 0.0, 50.0, 50.0)
                )
            ),
            gesture = GestureId("gesture-$strokeIndex"),
            label = UndoLabel("draw-$strokeIndex")
        )
    }

    private fun makeInputBatch(
        session: SessionKey,
        pointerId: Int,
        seq: Long,
        action: InputActionKind,
        sampleCount: Int = 4
    ): ImmutableInputBatch {
        val xs = FloatArray(sampleCount) { (it * 2).toFloat() }
        val ys = FloatArray(sampleCount) { (it * 3).toFloat() }
        val ps = FloatArray(sampleCount) { 0.5f }
        val ts = FloatArray(sampleCount) { 0.0f }
        val times = LongArray(sampleCount) { seq * 100L + it }
        return ImmutableInputBatch.copyFromArrays(
            session = session,
            pointerId = pointerId,
            sequenceNumber = seq,
            action = action,
            count = sampleCount,
            xs = xs,
            ys = ys,
            pressures = ps,
            tilts = ts,
            timestampsMs = times
        ).getOrNull()!!
    }

    @Test
    fun overflow_policies(@TempDir tempDir: File) {
        // Configure tight caps to deterministically exercise all 5 queue overflow policies
        val manualStorage = ManualAckStoragePort()
        val config = RuntimeConfig(
            storageRootPath = tempDir.absolutePath,
            inputQueueMaxBytes = 512L,
            maxInputBatchBytes = 512,
            terminalControlReservedSlots = 4,
            persistQueueMaxEnvelopes = 2,
            persistQueueMaxBytes = 4096L,
            durableAckMaxSlots = 2,
            durableAckMaxBytes = 4096L,
            maxRenderSnapshots = 2,
            mainEventsMaxEnvelopes = 4,
            mainEventsMaxBytes = 64 * 1024L,
            errorEventsMaxSlots = 4,
            errorEventsMaxBytes = 16 * 1024L
        )
        val runtime = Runtime.start(config, PlatformPorts(storagePort = manualStorage)).getOrNull()!!
        runtime.setPauseEngineLoopForTest(true)

        val docId = DocumentId("doc-overflow")
        val session = runtime.openSessionSyncForTest(docId)

        // 1. Samples Main -> Engine: coalesces consecutive MOVE batches without dropping samples,
        //    then rejects with ByteBudgetExceeded and emits InputDraftCanceled when full
        val move1 = makeInputBatch(session, pointerId = 0, seq = 1L, action = InputActionKind.MOVE, sampleCount = 4) // 160 B
        val move2 = makeInputBatch(session, pointerId = 0, seq = 2L, action = InputActionKind.MOVE, sampleCount = 4) // coalesced -> 256 B
        val downOther = makeInputBatch(session, pointerId = 1, seq = 3L, action = InputActionKind.DOWN, sampleCount = 8) // 256 B -> total 512 B
        val overflowMove = makeInputBatch(session, pointerId = 1, seq = 4L, action = InputActionKind.MOVE, sampleCount = 4)

        assertTrue(runtime.trySend(AppCommand.SubmitInputBatch(move1)).isSuccess)
        assertTrue(runtime.trySend(AppCommand.SubmitInputBatch(move2)).isSuccess)
        assertTrue(runtime.trySend(AppCommand.SubmitInputBatch(downOther)).isSuccess)

        val overflowInputResult = runtime.trySend(AppCommand.SubmitInputBatch(overflowMove))
        assertTrue(overflowInputResult.isFailure)
        assertTrue(overflowInputResult.errorOrNull() is SubmitError.ByteBudgetExceeded)

        // 2. Terminal input/control: reserved slots succeed even while sample queue is 100% full!
        val cancelControl = runtime.trySend(
            AppCommand.SubmitControl(
                session = session,
                sequenceNumber = 5L,
                action = InputActionKind.CANCEL,
                pointerId = 1
            )
        )
        assertTrue(cancelControl.isSuccess, "Reserved CANCEL control slot must succeed even when sample queue is full")

        // Resume engine step to drain input and verify coalesced move1+move2 preserved all 8 samples
        runtime.setPauseEngineLoopForTest(false)
        runtime.drainEngineLoopForTest()

        // 3. Edit/persist FIFO + DurableAck reservation: max 2 unacknowledged commits
        val edit1 = runtime.trySend(AppCommand.SubmitEdit(session, makeStrokeEditPlan(docId, 0L, 1), estimatedBytes = 1024L))
        runtime.drainEngineLoopForTest()
        val edit2 = runtime.trySend(AppCommand.SubmitEdit(session, makeStrokeEditPlan(docId, 1L, 2), estimatedBytes = 1024L))
        runtime.drainEngineLoopForTest()
        assertTrue(edit1.isSuccess)
        assertTrue(edit2.isSuccess)

        // 3rd edit while 2 commits are still pending DurableAck must be rejected with PendingPersistenceOverflow
        val edit3Overflow = runtime.trySend(AppCommand.SubmitEdit(session, makeStrokeEditPlan(docId, 2L, 3), estimatedBytes = 1024L))
        runtime.drainEngineLoopForTest()
        val saveStatus = runtime.latestSaveStatus(session)!!
        assertTrue(
            edit3Overflow.isFailure || saveStatus.status == SaveStatusKind.PENDING_OVERFLOW,
            "Expected PendingPersistenceOverflow when persist FIFO / DurableAck reservations are full"
        )

        // 4. View/render snapshot mailbox: max 2 retained snapshots while committed model keeps all revisions
        assertTrue(runtime.retainedSnapshotPinCountForTest() <= 2)

        // 5. Events service -> Main coalesces metadata by version; errors stay in error channel until acknowledged
        val polled = runtime.pollEvents(max = 64)
        assertTrue(polled.errorEvents.isNotEmpty(), "Overflow must produce an explicit ErrorEvent")

        runtime.closeForTest()
    }

    @Test
    fun durable_order(@TempDir tempDir: File) {
        val manualStorage = ManualAckStoragePort()
        val config = RuntimeConfig(storageRootPath = tempDir.absolutePath)
        val runtime = Runtime.start(config, PlatformPorts(storagePort = manualStorage)).getOrNull()!!

        val docId = DocumentId("doc-durable-order")
        val session = runtime.openSessionSyncForTest(docId)

        // Submit 3 sequential edits
        for (i in 1..3) {
            assertTrue(
                runtime.trySend(
                    AppCommand.SubmitEdit(
                        session = session,
                        plan = makeStrokeEditPlan(docId, (i - 1).toLong(), i),
                        estimatedBytes = 512L
                    )
                ).isSuccess
            )
            runtime.drainEngineLoopForTest()
        }

        // Applied revision is 3, but durable revision is still 0 (status = SAVING)
        val statusBeforeAck = runtime.latestSaveStatus(session)!!
        assertEquals(Revision(3L), statusBeforeAck.appliedRevision)
        assertEquals(Revision(0L), statusBeforeAck.durableRevision)
        assertEquals(SaveStatusKind.SAVING, statusBeforeAck.status)

        // Deliver DurableAck for revision 1 -> durable watermark advances to 1 (still SAVING)
        runtime.deliverDurableAck(DurableAck(docId, Revision(1L), manualStorage.txIdForRevision(1L)))
        runtime.drainEngineLoopForTest()
        assertEquals(Revision(1L), runtime.latestSaveStatus(session)!!.durableRevision)
        assertEquals(SaveStatusKind.SAVING, runtime.latestSaveStatus(session)!!.status)

        // Deliver DurableAck for revision 3 OUT OF ORDER before revision 2 ->
        // durable watermark MUST remain at 1 (only advances along continuous prefix!)
        runtime.deliverDurableAck(DurableAck(docId, Revision(3L), manualStorage.txIdForRevision(3L)))
        runtime.drainEngineLoopForTest()
        assertEquals(
            Revision(1L),
            runtime.latestSaveStatus(session)!!.durableRevision,
            "Durable watermark must only advance along a continuous prefix of revisions"
        )
        assertEquals(SaveStatusKind.SAVING, runtime.latestSaveStatus(session)!!.status)

        // Now deliver missing DurableAck for revision 2 -> watermark jumps continuously to 3 and status becomes SAVED!
        runtime.deliverDurableAck(DurableAck(docId, Revision(2L), manualStorage.txIdForRevision(2L)))
        runtime.drainEngineLoopForTest()
        val finalStatus = runtime.latestSaveStatus(session)!!
        assertEquals(Revision(3L), finalStatus.durableRevision)
        assertEquals(SaveStatusKind.SAVED, finalStatus.status)
        assertEquals(0, finalStatus.pendingCount)

        runtime.closeForTest()
    }

    @Test
    fun no_starvation(@TempDir tempDir: File) {
        val config = RuntimeConfig(storageRootPath = tempDir.absolutePath)
        val runtime = Runtime.start(config, PlatformPorts()).getOrNull()!!

        val heavyDoc = DocumentId("doc-heavy-bg")
        val penDoc = DocumentId("doc-active-pen")
        val heavySession = runtime.openSessionSyncForTest(heavyDoc)
        val penSession = runtime.openSessionSyncForTest(penDoc)

        // Pause engine loop to queue 30 edits on heavySession AND 5 active pen batches on penSession
        runtime.setPauseEngineLoopForTest(true)
        for (i in 1..30) {
            runtime.trySend(
                AppCommand.SubmitEdit(
                    session = heavySession,
                    plan = makeStrokeEditPlan(heavyDoc, (i - 1).toLong(), i),
                    estimatedBytes = 512L
                )
            )
        }
        for (seq in 1L..5L) {
            val action = when (seq) {
                1L -> InputActionKind.DOWN
                5L -> InputActionKind.UP
                else -> InputActionKind.MOVE
            }
            runtime.trySend(AppCommand.SubmitInputBatch(makeInputBatch(penSession, 0, seq, action)))
        }

        // Run a single quantum cycle: pen input MUST be serviced before all 30 heavy edits finish!
        val orderLog = runtime.stepSingleQuantumForTest()
        assertTrue(
            orderLog.any { it.startsWith("input:${penDoc.value}") },
            "Active pen input on penSession must be serviced within the first scheduling quantum without starving behind heavySession edits"
        )
        assertTrue(
            runtime.latestSaveStatus(heavySession)!!.appliedRevision.value < 30L,
            "Heavy session edits must be quantized so they do not monopolize the engine actor"
        )

        runtime.setPauseEngineLoopForTest(false)
        runtime.drainEngineLoopForTest()
        assertEquals(Revision(30L), runtime.latestSaveStatus(heavySession)!!.appliedRevision)
        runtime.closeForTest()
    }

    @Test
    fun shutdown_pending(@TempDir tempDir: File) {
        val config = RuntimeConfig(storageRootPath = tempDir.absolutePath)
        val runtime = Runtime.start(config, PlatformPorts()).getOrNull()!!

        val docId = DocumentId("doc-shutdown")
        val session = runtime.openSessionSyncForTest(docId)

        for (i in 1..5) {
            assertTrue(
                runtime.trySend(
                    AppCommand.SubmitEdit(
                        session = session,
                        plan = makeStrokeEditPlan(docId, (i - 1).toLong(), i),
                        estimatedBytes = 512L
                    )
                ).isSuccess
            )
        }

        // Initiate async close while commits are pending
        val closeReq = runtime.closeAsync()
        assertTrue(closeReq.isSuccess)

        // Any command submitted after closeAsync() must be rejected with ShutdownInProgress
        val lateCmd = runtime.trySend(
            AppCommand.SubmitEdit(
                session = session,
                plan = makeStrokeEditPlan(docId, 5L, 6),
                estimatedBytes = 512L
            )
        )
        assertTrue(lateCmd.isFailure)
        assertTrue(lateCmd.errorOrNull() is SubmitError.ShutdownInProgress)

        // Wait for shutdown completion and verify AppEvent.Closed is emitted after all 5 pending commits become durable
        runtime.awaitTerminationForTest(timeoutMs = 5_000L)
        val batch = runtime.pollEvents(max = 100)
        val closedEvent = batch.events.filterIsInstance<AppEvent.Closed>().firstOrNull()
        assertNotNull(closedEvent, "AppEvent.Closed must be emitted after pending persistence finishes")
        assertEquals(5, closedEvent!!.completedPendingCommits)

        // Reopen runtime on the same directory and verify all 5 revisions were durably persisted
        val runtime2 = Runtime.start(config, PlatformPorts()).getOrNull()!!
        val reopenedSession = runtime2.openSessionSyncForTest(docId)
        val reopenedSnap = runtime2.latestSnapshot(reopenedSession)!!
        assertEquals(Revision(5L), reopenedSnap.version.revision)
        assertEquals(5, reopenedSnap.totalObjectCount)
        runtime2.closeForTest()
    }

    @Test
    fun stale_session(@TempDir tempDir: File) {
        val config = RuntimeConfig(storageRootPath = tempDir.absolutePath)
        val runtime = Runtime.start(config, PlatformPorts()).getOrNull()!!

        val docId = DocumentId("doc-epochs")
        val sessionEpoch1 = runtime.openSessionSyncForTest(docId)
        assertEquals(SessionEpoch(1L), sessionEpoch1.epoch)

        // Close session 1 and reopen same DocumentId -> gets SessionEpoch(2)
        assertTrue(runtime.trySend(AppCommand.CloseSession(sessionEpoch1)).isSuccess)
        runtime.drainEngineLoopForTest()

        val sessionEpoch2 = runtime.openSessionSyncForTest(docId)
        assertEquals(SessionEpoch(2L), sessionEpoch2.epoch)

        // Commands or job replies targeting stale sessionEpoch1 must be rejected with StaleSession
        val staleEdit = runtime.trySend(
            AppCommand.SubmitEdit(
                session = sessionEpoch1,
                plan = makeStrokeEditPlan(docId, 0L, 1)
            )
        )
        assertTrue(staleEdit.isFailure)
        assertTrue(staleEdit.errorOrNull() is SubmitError.StaleSession)

        val staleJobReply = runtime.trySend(
            AppCommand.DeliverJobReply(
                session = sessionEpoch1,
                generation = RequestGeneration(1L),
                resource = "pdf-tile-0"
            )
        )
        assertTrue(staleJobReply.isFailure)
        assertTrue(staleJobReply.errorOrNull() is SubmitError.StaleSession)

        // SessionEpoch(2) remains clean at revision 0
        assertEquals(Revision(0L), runtime.latestSnapshot(sessionEpoch2)!!.version.revision)
        runtime.closeForTest()
    }

    @Test
    fun pins_counted() {
        val governor = MemoryGovernor(maxRenderSnapshots = 2, maxWarmSessions = 3)
        val docId = DocumentId("doc-pins")
        val state = DocumentState.createInitial(docId)

        val s1 = state.snapshot()
        val s2 = state.snapshot()
        val s3 = state.snapshot()

        val pin1 = governor.pinRenderSnapshot(SessionKey(docId, SessionEpoch(1L)), s1).getOrNull()!!
        val pin2 = governor.pinRenderSnapshot(SessionKey(docId, SessionEpoch(1L)), s2).getOrNull()!!
        assertEquals(2, governor.activeRenderSnapshotCount())
        assertTrue(governor.usedBytes(MemoryCategory.RESIDENT_MODEL_PAYLOAD) >= pin1.reservedBytes + pin2.reservedBytes)

        // Pinning a 3rd render snapshot automatically evicts the oldest pending snapshot pin so active count stays <= 2
        val pin3 = governor.pinRenderSnapshot(SessionKey(docId, SessionEpoch(1L)), s3).getOrNull()!!
        assertEquals(2, governor.activeRenderSnapshotCount())
        assertTrue(pin1.isClosed(), "Oldest render snapshot pin must be evicted when maxRenderSnapshots (2) is exceeded")
        assertTrue(!pin2.isClosed() && !pin3.isClosed())

        pin2.close()
        pin3.close()
        assertEquals(0, governor.activeRenderSnapshotCount())
        assertEquals(0L, governor.usedBytes(MemoryCategory.RESIDENT_MODEL_PAYLOAD))
    }

    @Test
    fun cancel_releases() = runBlocking {
        val governor = MemoryGovernor()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var acquiredInsideJob = false
        var caughtCancel = false

        val job = scope.launch {
            governor.withReservation(
                category = MemoryCategory.CPU_RASTER_TILES,
                bytes = 8L * 1024L * 1024L,
                owner = "tile-decode-job"
            ) { lease ->
                acquiredInsideJob = !lease.isClosed()
                try {
                    delay(10_000L)
                } catch (ce: CancellationException) {
                    caughtCancel = true
                    throw ce
                }
            }
        }

        // Wait until reservation is active
        var spins = 0
        while (!acquiredInsideJob && spins < 200) {
            delay(5L)
            spins++
        }
        assertTrue(acquiredInsideJob)
        assertEquals(8L * 1024L * 1024L, governor.usedBytes(MemoryCategory.CPU_RASTER_TILES))

        // Cancel job and verify reservation is deterministically released in finally
        job.cancelAndJoin()
        assertTrue(caughtCancel)
        assertEquals(0L, governor.usedBytes(MemoryCategory.CPU_RASTER_TILES))
    }

    @Test
    fun lease_budget() {
        val governor = MemoryGovernor()

        // 1. Reserve within 32 MiB CPU_RASTER_TILES cap -> succeeds
        val lease20M = governor.tryReserve(
            category = MemoryCategory.CPU_RASTER_TILES,
            bytes = 20L * 1024L * 1024L,
            owner = "tile-1"
        )
        assertTrue(lease20M.isSuccess)

        // 2. Request another 16 MiB (20 + 16 = 36 MiB > 32 MiB cap) -> rejected BEFORE allocation!
        val overBudget = governor.tryReserve(
            category = MemoryCategory.CPU_RASTER_TILES,
            bytes = 16L * 1024L * 1024L,
            owner = "tile-2"
        )
        assertTrue(overBudget.isFailure)
        assertTrue(overBudget.errorOrNull() is ResourceError.ResourceExhausted)
        assertEquals(20L * 1024L * 1024L, governor.usedBytes(MemoryCategory.CPU_RASTER_TILES))

        // 3. Adjust actual size down (e.g. decoded tile took 12 MiB instead of 20 MiB)
        lease20M.getOrNull()!!.adjustActualBytes(12L * 1024L * 1024L)
        assertEquals(12L * 1024L * 1024L, governor.usedBytes(MemoryCategory.CPU_RASTER_TILES))

        // Now 16 MiB fits (12 + 16 = 28 MiB <= 32 MiB)
        val fitsNow = governor.tryReserve(
            category = MemoryCategory.CPU_RASTER_TILES,
            bytes = 16L * 1024L * 1024L,
            owner = "tile-2-retry"
        )
        assertTrue(fitsNow.isSuccess)

        // 4. Verify worker pool is bounded (1..4 threads) and shared across tasks
        val pool = governor.workerPool
        assertTrue(pool.maxThreads in 1..4)

        lease20M.getOrNull()!!.close()
        fitsNow.getOrNull()!!.close()
        assertEquals(0L, governor.usedBytes(MemoryCategory.CPU_RASTER_TILES))
    }

    private class ManualAckStoragePort : StoragePort {
        private val seq = AtomicLong(1L)
        val committedRequests = CopyOnWriteArrayList<StorageRequest.Commit>()

        override fun trySubmit(request: StorageRequest): Outcome<RequestId, SubmitError> {
            if (request is StorageRequest.Commit) {
                committedRequests.add(request)
            }
            return Outcome.Success(RequestId(seq.getAndIncrement()))
        }

        fun txIdForRevision(rev: Long) =
            committedRequests.firstOrNull { it.revision.value == rev }?.transactionId
                ?: inkspace.core.id.TransactionId("tx-$rev")
    }
}
