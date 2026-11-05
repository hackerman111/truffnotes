package inkspace.store

import inkspace.core.error.ResourceError
import inkspace.core.error.StorageError
import inkspace.core.id.AssetId
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.TransactionId
import inkspace.core.id.UndoLabel
import inkspace.core.id.VersionId
import inkspace.core.limits.Limits
import inkspace.core.model.DocumentState
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.MoveObject
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.StickyPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.ports.ResourceRequest
import inkspace.core.ports.StorageReply
import inkspace.core.ports.StorageRequest
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class F03StorageContractTest {

    private val docId = DocumentId("doc-store-001")
    private val defaultSpace = SpaceId("space-1")
    private val defaultPage = PageId("page-1")
    private val defaultLayer = LayerId("layer-1")
    private val limits = Limits()

    @Test
    fun commit_ack(@TempDir tempDir: File) {
        val faultController = FaultInjectionController()
        val store = DocumentStore(rootDir = tempDir, faultController = faultController)

        val openRes = store.execute(StorageRequest.Open(docId))
        assertTrue(openRes.isSuccess)
        val opened = openRes.getOrNull() as StorageReply.Opened
        assertEquals(Revision(0L), opened.durableRevision)

        // Verify SQLite PRAGMAs on open connection (WAL, FULL=2, foreign_keys=1)
        val pragmas = store.inspectPragmas(docId)
        assertEquals("wal", pragmas.journalMode.lowercase())
        assertEquals(2, pragmas.synchronous) // 2 = FULL
        assertEquals(1, pragmas.foreignKeys)

        // Stage a provisional draft chunk -> must NOT advance durableRevision
        val stageDraft = store.execute(
            StorageRequest.StageDraftChunk(
                documentId = docId,
                gestureId = GestureId("g-draft-1"),
                chunkIndex = 0,
                encodedBytes = ByteArray(128) { it.toByte() }
            )
        )
        assertTrue(stageDraft.isSuccess)
        assertEquals(Revision(0L), store.durableRevision(docId))

        // Build and apply a transaction in memory, then commit to store
        val memState = DocumentState.createInitial(docId)
        val plan = EditPlan(
            base = memState.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = ObjectId("stroke-1"),
                    payload = StrokePayload(
                        brushId = "ballpen",
                        color = 0xFF112233.toInt(),
                        baseWidthPt = 2.0,
                        origin = DocPoint(10.0, 20.0),
                        samples = listOf(StrokeSamplePoint(0f, 0f, 0.5f), StrokeSamplePoint(5f, 8f, 0.7f))
                    ),
                    bounds = DocRect(10.0, 20.0, 15.0, 28.0)
                )
            ),
            gesture = GestureId("g-commit-1"),
            label = UndoLabel("Draw stroke 1")
        )
        val applied = applyPlan(memState, validatePlan(memState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!

        val commitRes = store.execute(
            StorageRequest.Commit(
                revision = applied.revision,
                payloadBytes = 256L,
                transactionId = TransactionId("tx-001"),
                expectedDurableBase = Revision(0L),
                writeSet = applied.writeSet
            )
        )
        assertTrue(commitRes.isSuccess)
        val committed = commitRes.getOrNull() as StorageReply.Committed
        assertEquals(docId, committed.ack.document)
        assertEquals(Revision(1L), committed.ack.revision)
        assertEquals(TransactionId("tx-001"), committed.ack.transactionId)
        assertFalse(committed.ack.wasDeduplicated)
        assertEquals(Revision(1L), store.durableRevision(docId))

        store.execute(StorageRequest.Close(docId))
    }

    @Test
    fun reopen(@TempDir tempDir: File) {
        val store1 = DocumentStore(rootDir = tempDir)
        store1.execute(StorageRequest.Open(docId))

        val memState = DocumentState.createInitial(docId)
        for (i in 1..5) {
            val plan = EditPlan(
                base = memState.version,
                mutations = listOf(
                    InsertObject(
                        space = defaultSpace,
                        layer = defaultLayer,
                        id = ObjectId("obj-$i"),
                        payload = StickyPayload(color = i * 100, content = "Note $i"),
                        bounds = DocRect(i * 10.0, i * 10.0, i * 10.0 + 40.0, i * 10.0 + 40.0)
                    )
                ),
                gesture = GestureId("g-$i"),
                label = UndoLabel("Insert $i")
            )
            val applied = applyPlan(memState, validatePlan(memState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!
            val res = store1.execute(
                StorageRequest.Commit(
                    revision = applied.revision,
                    transactionId = TransactionId("tx-reopen-$i"),
                    expectedDurableBase = Revision((i - 1).toLong()),
                    writeSet = applied.writeSet
                )
            )
            assertTrue(res.isSuccess)
        }
        val expectedFingerprint = memState.snapshot().contentFingerprint()
        store1.execute(StorageRequest.Close(docId))

        // Reopen in a fresh DocumentStore instance
        val store2 = DocumentStore(rootDir = tempDir)
        val reopenRes = store2.execute(StorageRequest.Open(docId, createIfMissing = false))
        assertTrue(reopenRes.isSuccess)
        val opened2 = reopenRes.getOrNull() as StorageReply.Opened
        assertEquals(Revision(5L), opened2.durableRevision)
        assertEquals(5, opened2.totalObjects)

        val recoveredState = store2.currentState(docId)!!
        assertEquals(expectedFingerprint, recoveredState.snapshot().contentFingerprint())
        assertTrue(store2.verifyIntegrity(docId))
        store2.execute(StorageRequest.Close(docId))
    }

    @Test
    fun retry_idempotence(@TempDir tempDir: File) {
        val store = DocumentStore(rootDir = tempDir)
        store.execute(StorageRequest.Open(docId))

        val memState = DocumentState.createInitial(docId)
        val plan = EditPlan(
            base = memState.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = ObjectId("obj-idem-1"),
                    payload = StickyPayload(color = 42, content = "Idempotent stroke"),
                    bounds = DocRect(0.0, 0.0, 50.0, 50.0)
                )
            ),
            gesture = GestureId("g-idem"),
            label = UndoLabel("Idempotent insert")
        )
        val applied = applyPlan(memState, validatePlan(memState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!

        val commitReq = StorageRequest.Commit(
            revision = applied.revision,
            transactionId = TransactionId("tx-idempotent-001"),
            expectedDurableBase = Revision(0L),
            writeSet = applied.writeSet
        )

        val firstRes = store.execute(commitReq)
        assertTrue(firstRes.isSuccess)
        val firstAck = (firstRes.getOrNull() as StorageReply.Committed).ack
        assertFalse(firstAck.wasDeduplicated)

        // Retry the exact same commit request with the same TransactionId
        val secondRes = store.execute(commitReq)
        assertTrue(secondRes.isSuccess)
        val secondAck = (secondRes.getOrNull() as StorageReply.Committed).ack
        assertTrue(secondAck.wasDeduplicated)
        assertEquals(Revision(1L), secondAck.revision)
        assertEquals(1, store.currentState(docId)!!.totalObjectCount)

        store.execute(StorageRequest.Close(docId))
    }

    @Test
    fun stale_base(@TempDir tempDir: File) {
        val store = DocumentStore(rootDir = tempDir)
        store.execute(StorageRequest.Open(docId))

        val memState = DocumentState.createInitial(docId)
        val plan = EditPlan(
            base = memState.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, ObjectId("obj-1"), StickyPayload(1, "1"), DocRect(0.0, 0.0, 10.0, 10.0))
            ),
            gesture = GestureId("g-1"),
            label = UndoLabel("Insert 1")
        )
        val applied = applyPlan(memState, validatePlan(memState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!

        // Try committing with expectedDurableBase = 5 when actual durable base is 0
        val staleCommit = store.execute(
            StorageRequest.Commit(
                revision = applied.revision,
                transactionId = TransactionId("tx-stale"),
                expectedDurableBase = Revision(5L),
                writeSet = applied.writeSet
            )
        )
        assertTrue(staleCommit.isFailure)
        assertTrue(staleCommit.errorOrNull() is StorageError.StaleDurableBase)
        assertEquals(Revision(0L), store.durableRevision(docId))

        store.execute(StorageRequest.Close(docId))
    }

    @Test
    fun disk_full(@TempDir tempDir: File) {
        val faultController = FaultInjectionController()
        val store = DocumentStore(rootDir = tempDir, faultController = faultController)
        store.execute(StorageRequest.Open(docId))

        val memState = DocumentState.createInitial(docId)
        val plan1 = EditPlan(
            base = memState.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, ObjectId("obj-ok"), StickyPayload(1, "OK"), DocRect(0.0, 0.0, 20.0, 20.0))
            ),
            gesture = GestureId("g-ok"),
            label = UndoLabel("OK")
        )
        val app1 = applyPlan(memState, validatePlan(memState.snapshot(), plan1, limits).getOrNull()!!).getOrNull()!!
        assertTrue(
            store.execute(
                StorageRequest.Commit(
                    revision = app1.revision,
                    transactionId = TransactionId("tx-ok"),
                    expectedDurableBase = Revision(0L),
                    writeSet = app1.writeSet
                )
            ).isSuccess
        )

        // Inject disk full on next DB commit
        faultController.nextFault = FaultPoint.DISK_FULL_ON_DB_COMMIT

        val plan2 = EditPlan(
            base = memState.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, ObjectId("obj-fail"), StickyPayload(2, "Fail"), DocRect(30.0, 0.0, 50.0, 20.0))
            ),
            gesture = GestureId("g-fail"),
            label = UndoLabel("Should fail on ENOSPC")
        )
        val app2 = applyPlan(memState, validatePlan(memState.snapshot(), plan2, limits).getOrNull()!!).getOrNull()!!
        val failRes = store.execute(
            StorageRequest.Commit(
                revision = app2.revision,
                transactionId = TransactionId("tx-enospc"),
                expectedDurableBase = Revision(1L),
                writeSet = app2.writeSet
            )
        )
        assertTrue(failRes.isFailure)
        assertTrue(failRes.errorOrNull() is StorageError.DiskFull)

        // Revision 1 must remain intact and pass integrity check
        assertEquals(Revision(1L), store.durableRevision(docId))
        assertEquals(1, store.currentState(docId)!!.totalObjectCount)
        assertTrue(store.verifyIntegrity(docId))

        store.execute(StorageRequest.Close(docId))
    }

    @Test
    fun asset_before_ref(@TempDir tempDir: File) {
        val faultController = FaultInjectionController()
        val store = DocumentStore(rootDir = tempDir, faultController = faultController)
        store.execute(StorageRequest.Open(docId))

        val assetBytes = "PNG_IMAGE_PAYLOAD_V1_BYTES".encodeToByteArray()
        val stagedAsset = store.stageAsset(docId, stagingId = "op-img-1", bytes = assetBytes).getOrNull()!!
        val unstageAssetId = AssetId("sha256:0000000000000000000000000000000000000000000000000000000000000000")

        // 1. Attempting to commit a WriteSet referencing an unstaged/missing asset fails with MissingAssetReference
        val memState1 = DocumentState.createInitial(docId)
        val badPlan = EditPlan(
            base = memState1.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = ObjectId("img-missing"),
                    payload = ImagePayload(unstageAssetId, 100, 100),
                    bounds = DocRect(0.0, 0.0, 100.0, 100.0)
                )
            ),
            gesture = GestureId("g-bad-img"),
            label = UndoLabel("Bad image")
        )
        val badApplied = applyPlan(memState1, validatePlan(memState1.snapshot(), badPlan, limits).getOrNull()!!).getOrNull()!!
        val badCommit = store.execute(
            StorageRequest.Commit(
                revision = badApplied.revision,
                transactionId = TransactionId("tx-bad-asset"),
                expectedDurableBase = Revision(0L),
                writeSet = badApplied.writeSet
            )
        )
        assertTrue(badCommit.isFailure)
        assertTrue(badCommit.errorOrNull() is StorageError.MissingAssetReference)
        assertEquals(Revision(0L), store.durableRevision(docId))

        // 2. Crash after asset fsync/publish but BEFORE DB commit leaves orphan asset, DB stays at rev 0
        val memState2 = DocumentState.createInitial(docId)
        val goodPlan = EditPlan(
            base = memState2.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = ObjectId("img-good"),
                    payload = ImagePayload(stagedAsset.assetId, 800, 600),
                    bounds = DocRect(0.0, 0.0, 400.0, 300.0)
                )
            ),
            gesture = GestureId("g-good-img"),
            label = UndoLabel("Good image")
        )
        val goodApplied = applyPlan(memState2, validatePlan(memState2.snapshot(), goodPlan, limits).getOrNull()!!).getOrNull()!!

        faultController.nextFault = FaultPoint.CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT
        val abortedCommit = store.execute(
            StorageRequest.Commit(
                revision = goodApplied.revision,
                transactionId = TransactionId("tx-aborted-asset"),
                expectedDurableBase = Revision(0L),
                writeSet = goodApplied.writeSet,
                stagedAssets = listOf(stagedAsset)
            )
        )
        assertTrue(abortedCommit.isFailure)
        assertEquals(Revision(0L), store.durableRevision(docId))

        // Acquire a lease on the orphan asset -> GC must NOT delete it while lease is open!
        val leaseOutcome = store.read(ResourceRequest(stagedAsset.assetId, byteBudget = 1024L))
        assertTrue(leaseOutcome.isSuccess)
        val activeLease = leaseOutcome.getOrNull()!!

        val gcWhileLeased = store.execute(StorageRequest.Collect(docId)).getOrNull() as StorageReply.Collected
        assertEquals(0, gcWhileLeased.deletedOrphanAssets)

        // Release lease -> now GC deletes the unreferenced orphan asset!
        activeLease.close()
        val gcAfterRelease = store.execute(StorageRequest.Collect(docId)).getOrNull() as StorageReply.Collected
        assertEquals(1, gcAfterRelease.deletedOrphanAssets)

        // 3. Re-stage and commit properly -> asset is reachable and verifying corrupted bytes returns CorruptedResource
        val restaged = store.stageAsset(docId, stagingId = "op-img-2", bytes = assetBytes).getOrNull()!!
        val okCommit = store.execute(
            StorageRequest.Commit(
                revision = goodApplied.revision,
                transactionId = TransactionId("tx-ok-asset"),
                expectedDurableBase = Revision(0L),
                writeSet = goodApplied.writeSet,
                stagedAssets = listOf(restaged)
            )
        )
        assertTrue(okCommit.isSuccess)

        // Corrupt the asset file on disk and verify read() detects SHA-256 mismatch instead of returning empty image
        val assetFile = store.assetFile(docId, restaged.assetId)
        assetFile.writeBytes("CORRUPTED_BYTES".encodeToByteArray())
        val corruptRead = store.read(ResourceRequest(restaged.assetId, byteBudget = 1024L))
        assertTrue(corruptRead.isFailure)
        assertTrue(corruptRead.errorOrNull() is ResourceError.CorruptedResource)

        store.execute(StorageRequest.Close(docId))
    }

    @Test
    fun truncated_wal(@TempDir tempDir: File) {
        val store1 = DocumentStore(rootDir = tempDir)
        store1.execute(StorageRequest.Open(docId))

        val memState = DocumentState.createInitial(docId)
        for (i in 1..3) {
            val plan = EditPlan(
                base = memState.version,
                mutations = listOf(
                    InsertObject(defaultSpace, defaultLayer, ObjectId("wal-obj-$i"), StickyPayload(i, "WAL $i"), DocRect(0.0, 0.0, 20.0, 20.0))
                ),
                gesture = GestureId("g-wal-$i"),
                label = UndoLabel("WAL $i")
            )
            val app = applyPlan(memState, validatePlan(memState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!
            assertTrue(
                store1.execute(
                    StorageRequest.Commit(
                        revision = app.revision,
                        transactionId = TransactionId("tx-wal-$i"),
                        expectedDurableBase = Revision((i - 1).toLong()),
                        writeSet = app.writeSet
                    )
                ).isSuccess
            )
        }
        val expectedFingerprint = memState.snapshot().contentFingerprint()
        store1.simulateAbruptCloseWithTruncatedWalTail(docId, trailingGarbageBytes = 73)

        val walFile = File(tempDir, "documents/${docId.value}/document.sqlite-wal")
        val fixtureFile = File("../../fixtures/corrupt/store/truncated-wal.bin")
        if (!fixtureFile.exists() && walFile.exists()) {
            fixtureFile.parentFile?.mkdirs()
            val bytes = walFile.readBytes()
            val sample = if (bytes.size > 512) bytes.copyOfRange(bytes.size - 512, bytes.size) else bytes
            fixtureFile.writeBytes(sample)
        }
        assertTrue(fixtureFile.exists(), "Expected corrupt WAL fixture at ${fixtureFile.absolutePath}")

        // Reopen after truncated WAL tail: SQLite recovers valid prefix (3 revisions) cleanly
        val store2 = DocumentStore(rootDir = tempDir)
        val reopen = store2.execute(StorageRequest.Open(docId, createIfMissing = false))
        assertTrue(reopen.isSuccess)
        val opened = reopen.getOrNull() as StorageReply.Opened
        assertEquals(Revision(3L), opened.durableRevision)
        assertEquals(expectedFingerprint, store2.currentState(docId)!!.snapshot().contentFingerprint())
        assertTrue(store2.verifyIntegrity(docId))
        store2.execute(StorageRequest.Close(docId))
    }

    @Test
    fun backup_consistent(@TempDir tempDir: File) {
        val store = DocumentStore(rootDir = tempDir)
        store.execute(StorageRequest.Open(docId))

        val memState = DocumentState.createInitial(docId)
        for (i in 1..4) {
            val plan = EditPlan(
                base = memState.version,
                mutations = listOf(
                    InsertObject(
                        space = defaultSpace,
                        layer = defaultLayer,
                        id = ObjectId("bkp-obj-$i"),
                        payload = StickyPayload(i, "Backup item $i"),
                        bounds = DocRect(i * 10.0, 0.0, i * 10.0 + 30.0, 30.0)
                    )
                ),
                gesture = GestureId("g-bkp-$i"),
                label = UndoLabel("Backup $i")
            )
            val app = applyPlan(memState, validatePlan(memState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!
            store.execute(
                StorageRequest.Commit(
                    revision = app.revision,
                    transactionId = TransactionId("tx-bkp-$i"),
                    expectedDurableBase = Revision((i - 1).toLong()),
                    writeSet = app.writeSet
                )
            )
        }

        // Create checkpoint version at revision 4
        val verRes = store.execute(
            StorageRequest.Version(
                documentId = docId,
                versionId = VersionId("ver-001"),
                label = "Checkpoint 4",
                isPinned = true
            )
        )
        assertTrue(verRes.isSuccess)

        // Create online consistent backup to external target path while DB is open
        val backupDir = File(tempDir, "backup_copy")
        val bkpFile = File(backupDir, "documents/${docId.value}/document.sqlite")
        val bkpRes = store.execute(StorageRequest.Backup(docId, bkpFile.absolutePath))
        assertTrue(bkpRes.isSuccess)

        // Lazy chunk read with byteBudget lease
        val readRes = store.execute(
            StorageRequest.Read(
                documentId = docId,
                spaceId = defaultSpace,
                objectIds = listOf(ObjectId("bkp-obj-1"), ObjectId("bkp-obj-2")),
                byteBudget = 65_536L
            )
        )
        assertTrue(readRes.isSuccess)
        val readReply = readRes.getOrNull() as StorageReply.ReadResult
        assertEquals(2, readReply.objects.size)
        readReply.lease.close()

        // Spill and reload history record
        store.spillHistoryEntry(
            docId = docId,
            gestureId = GestureId("g-spilled-1"),
            label = UndoLabel("Spilled move"),
            mutations = listOf(MoveObject(ObjectId("bkp-obj-1"), 5.0, 5.0))
        )
        val spilled = store.loadSpilledHistory(docId)
        assertEquals(1, spilled.size)
        assertEquals(GestureId("g-spilled-1"), spilled[0].first)

        // Open the backup in a second DocumentStore and verify consistency
        val backupStore = DocumentStore(rootDir = backupDir)
        val openBkp = backupStore.execute(StorageRequest.Open(docId, createIfMissing = false))
        assertTrue(openBkp.isSuccess)
        val bkpOpened = openBkp.getOrNull() as StorageReply.Opened
        assertEquals(Revision(4L), bkpOpened.durableRevision)
        assertEquals(memState.snapshot().contentFingerprint(), backupStore.currentState(docId)!!.snapshot().contentFingerprint())
        assertTrue(backupStore.verifyIntegrity(docId))

        backupStore.execute(StorageRequest.Close(docId))
        store.execute(StorageRequest.Close(docId))
    }
}
