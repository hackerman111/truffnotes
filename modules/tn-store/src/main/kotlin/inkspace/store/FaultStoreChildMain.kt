package inkspace.store

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.TransactionId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocumentState
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.MoveObject
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.StickyPayload
import inkspace.core.ports.StorageReply
import inkspace.core.ports.StorageRequest
import inkspace.core.unit.DocRect
import java.io.File
import kotlin.random.Random
import kotlin.system.exitProcess

object FaultStoreChildMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 4) {
            System.err.println("Usage: FaultStoreChildMain <workDir> <seed> <runs> <reportFile>")
            exitProcess(2)
        }
        val workDir = File(args[0])
        val seed = args[1].toLong()
        val runs = args[2].toInt()
        val reportFile = File(args[3])

        val rng = Random(seed)
        val limits = Limits()
        val spaceId = SpaceId("space-1")
        val layerId = LayerId("layer-1")

        var totalSchedules = 0
        var totalCommittedTx = 0
        var totalInjectedFaults = 0
        var durableLossCount = 0
        var integrityFailures = 0
        val faultCounts = LinkedHashMap<String, Int>()

        val startTimeNs = System.nanoTime()

        for (runIdx in 0 until runs) {
            val runDir = File(workDir, "run_$runIdx")
            runDir.mkdirs()

            val docId = DocumentId("doc-fault-$runIdx")
            val faultController = FaultInjectionController()
            val store = DocumentStore(rootDir = runDir, faultController = faultController, limits = limits)

            val openRes = store.execute(StorageRequest.Open(docId))
            if (openRes.isFailure) {
                System.err.println("Failed initial open on run $runIdx: ${openRes.errorOrNull()}")
                exitProcess(1)
            }

            // Reference state tracking ONLY durably committed transactions
            val durableRefState = DocumentState.createInitial(docId)
            var durableRev = 0L

            val stepsBeforeFault = rng.nextInt(1, 5)
            for (step in 1..stepsBeforeFault) {
                val objId = ObjectId("obj-$runIdx-$step")
                val plan = EditPlan(
                    base = durableRefState.version,
                    mutations = listOf(
                        InsertObject(
                            space = spaceId,
                            layer = layerId,
                            id = objId,
                            payload = StickyPayload(color = step, content = "Run $runIdx Step $step"),
                            bounds = DocRect(step * 5.0, step * 5.0, step * 5.0 + 20.0, step * 5.0 + 20.0)
                        )
                    ),
                    gesture = GestureId("g-$runIdx-$step"),
                    label = UndoLabel("Insert $step")
                )
                val applied = applyPlan(
                    durableRefState,
                    validatePlan(durableRefState.snapshot(), plan, limits).getOrNull()!!
                ).getOrNull()!!

                val commitRes = store.execute(
                    StorageRequest.Commit(
                        revision = applied.revision,
                        transactionId = TransactionId("tx-$runIdx-$step"),
                        expectedDurableBase = Revision(durableRev),
                        writeSet = applied.writeSet
                    )
                )
                if (commitRes.isFailure) {
                    System.err.println("Unexpected failure on normal commit run $runIdx step $step: ${commitRes.errorOrNull()}")
                    exitProcess(1)
                }
                durableRev = applied.revision.value
                totalCommittedTx++
            }

            // Pick a seeded fault scenario for the final operation before reopen
            val scenario = rng.nextInt(6)
            totalInjectedFaults++

            when (scenario) {
                0 -> {
                    faultCounts["DISK_FULL_ON_ASSET_WRITE"] = (faultCounts["DISK_FULL_ON_ASSET_WRITE"] ?: 0) + 1
                    faultController.nextFault = FaultPoint.DISK_FULL_ON_ASSET_WRITE
                    val stageRes = store.stageAsset(docId, "stg-$runIdx", "ASSET_$runIdx".encodeToByteArray())
                    check(stageRes.isFailure) { "Expected DISK_FULL_ON_ASSET_WRITE failure" }
                    store.execute(StorageRequest.Close(docId))
                }
                1 -> {
                    faultCounts["CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT"] =
                        (faultCounts["CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT"] ?: 0) + 1
                    val staged = store.stageAsset(docId, "stg-$runIdx", "ASSET_$runIdx".encodeToByteArray()).getOrNull()!!
                    val tempState = durableRefState.copyForCandidate()
                    val plan = EditPlan(
                        base = tempState.version,
                        mutations = listOf(
                            InsertObject(
                                space = spaceId,
                                layer = layerId,
                                id = ObjectId("img-$runIdx"),
                                payload = ImagePayload(staged.assetId, 100, 100),
                                bounds = DocRect(0.0, 0.0, 50.0, 50.0)
                            )
                        ),
                        gesture = GestureId("g-img-$runIdx"),
                        label = UndoLabel("Insert image")
                    )
                    val app = applyPlan(tempState, validatePlan(tempState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!
                    faultController.nextFault = FaultPoint.CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT
                    val commitRes = store.execute(
                        StorageRequest.Commit(
                            revision = app.revision,
                            transactionId = TransactionId("tx-img-$runIdx"),
                            expectedDurableBase = Revision(durableRev),
                            writeSet = app.writeSet,
                            stagedAssets = listOf(staged)
                        )
                    )
                    check(commitRes.isFailure) { "Expected CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT failure" }
                    store.execute(StorageRequest.Close(docId))
                }
                2 -> {
                    faultCounts["DISK_FULL_ON_DB_COMMIT"] = (faultCounts["DISK_FULL_ON_DB_COMMIT"] ?: 0) + 1
                    val tempState = durableRefState.copyForCandidate()
                    val plan = EditPlan(
                        base = tempState.version,
                        mutations = listOf(MoveObject(ObjectId("obj-$runIdx-1"), 10.0, 10.0)),
                        gesture = GestureId("g-enospc-$runIdx"),
                        label = UndoLabel("Move")
                    )
                    val app = applyPlan(tempState, validatePlan(tempState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!
                    faultController.nextFault = FaultPoint.DISK_FULL_ON_DB_COMMIT
                    val res = store.execute(
                        StorageRequest.Commit(
                            revision = app.revision,
                            transactionId = TransactionId("tx-enospc-$runIdx"),
                            expectedDurableBase = Revision(durableRev),
                            writeSet = app.writeSet
                        )
                    )
                    check(res.isFailure) { "Expected DISK_FULL_ON_DB_COMMIT failure" }
                    store.execute(StorageRequest.Close(docId))
                }
                3 -> {
                    faultCounts["CRASH_DURING_DB_TRANSACTION"] = (faultCounts["CRASH_DURING_DB_TRANSACTION"] ?: 0) + 1
                    val tempState = durableRefState.copyForCandidate()
                    val plan = EditPlan(
                        base = tempState.version,
                        mutations = listOf(MoveObject(ObjectId("obj-$runIdx-1"), 15.0, 15.0)),
                        gesture = GestureId("g-midtx-$runIdx"),
                        label = UndoLabel("Move mid-tx")
                    )
                    val app = applyPlan(tempState, validatePlan(tempState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!
                    faultController.nextFault = FaultPoint.CRASH_DURING_DB_TRANSACTION
                    val res = store.execute(
                        StorageRequest.Commit(
                            revision = app.revision,
                            transactionId = TransactionId("tx-midtx-$runIdx"),
                            expectedDurableBase = Revision(durableRev),
                            writeSet = app.writeSet
                        )
                    )
                    check(res.isFailure) { "Expected CRASH_DURING_DB_TRANSACTION failure" }
                    store.execute(StorageRequest.Close(docId))
                }
                4 -> {
                    // Crash AFTER DB commit before ACK -> DB has committed the new state, so durableRefState advances!
                    faultCounts["CRASH_AFTER_DB_COMMIT_BEFORE_ACK"] =
                        (faultCounts["CRASH_AFTER_DB_COMMIT_BEFORE_ACK"] ?: 0) + 1
                    val plan = EditPlan(
                        base = durableRefState.version,
                        mutations = listOf(MoveObject(ObjectId("obj-$runIdx-1"), 20.0, 20.0)),
                        gesture = GestureId("g-postcommit-$runIdx"),
                        label = UndoLabel("Move post-commit")
                    )
                    val app = applyPlan(
                        durableRefState,
                        validatePlan(durableRefState.snapshot(), plan, limits).getOrNull()!!
                    ).getOrNull()!!
                    faultController.nextFault = FaultPoint.CRASH_AFTER_DB_COMMIT_BEFORE_ACK
                    val txId = TransactionId("tx-postcommit-$runIdx")
                    val res = store.execute(
                        StorageRequest.Commit(
                            revision = app.revision,
                            transactionId = txId,
                            expectedDurableBase = Revision(durableRev),
                            writeSet = app.writeSet
                        )
                    )
                    check(res.isFailure) { "Expected CRASH_AFTER_DB_COMMIT_BEFORE_ACK failure" }
                    durableRev = app.revision.value
                    totalCommittedTx++
                    store.execute(StorageRequest.Close(docId))
                }
                else -> {
                    faultCounts["TRUNCATED_WAL_TAIL"] = (faultCounts["TRUNCATED_WAL_TAIL"] ?: 0) + 1
                    store.simulateAbruptCloseWithTruncatedWalTail(docId, trailingGarbageBytes = rng.nextInt(17, 129))
                }
            }

            // Reopen in a fresh store and verify durable prefix + SQLite integrity + GC
            val recoveredStore = DocumentStore(rootDir = runDir, limits = limits)
            val reopenOutcome = recoveredStore.execute(StorageRequest.Open(docId, createIfMissing = false))
            if (reopenOutcome.isFailure) {
                durableLossCount++
                System.err.println("Reopen failed on run $runIdx: ${reopenOutcome.errorOrNull()}")
                exitProcess(1)
            }

            val opened = reopenOutcome.getOrNull() as StorageReply.Opened
            val recoveredState = recoveredStore.currentState(docId)!!
            val expectedFingerprint = durableRefState.snapshot().contentFingerprint()
            val actualFingerprint = recoveredState.snapshot().contentFingerprint()

            if (opened.durableRevision.value != durableRev || actualFingerprint != expectedFingerprint) {
                durableLossCount++
                System.err.println(
                    "Durable prefix mismatch on run $runIdx: expected rev=$durableRev fp=$expectedFingerprint, " +
                        "got rev=${opened.durableRevision.value} fp=$actualFingerprint"
                )
                exitProcess(1)
            }

            if (!recoveredStore.verifyIntegrity(docId)) {
                integrityFailures++
                System.err.println("SQLite integrity check failed on run $runIdx")
                exitProcess(1)
            }

            recoveredStore.execute(StorageRequest.Collect(docId))
            recoveredStore.execute(StorageRequest.Close(docId))
            runDir.deleteRecursively()
            totalSchedules++
        }

        val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000L
        val runtime = Runtime.getRuntime()
        val usedHeapBytes = runtime.totalMemory() - runtime.freeMemory()

        val faultBreakdownJson = faultCounts.entries.joinToString(", ") { (k, v) -> "\"$k\": $v" }
        val jsonReport =
            """
            {
              "phase": "F03",
              "task": "faultStore",
              "seed": $seed,
              "runs": $totalSchedules,
              "totalCommittedTransactions": $totalCommittedTx,
              "totalInjectedFaults": $totalInjectedFaults,
              "durablePrefixLossCount": $durableLossCount,
              "sqliteIntegrityFailures": $integrityFailures,
              "usedHeapBytesAfterRuns": $usedHeapBytes,
              "elapsedMs": $elapsedMs,
              "faultScenarioCounts": { $faultBreakdownJson },
              "status": "PASS"
            }
            """.trimIndent()

        reportFile.parentFile?.mkdirs()
        reportFile.writeText(jsonReport + "\n")
        println("FaultStoreChildMain completed: runs=$totalSchedules, loss=$durableLossCount, integrityFailures=$integrityFailures, elapsedMs=$elapsedMs")
    }

    private fun DocumentState.copyForCandidate(): DocumentState {
        val snap = this.snapshot()
        val copy = DocumentState.createInitial(snap.version.document, this.kind)
        val fieldVersion = DocumentState::class.java.getDeclaredField("currentVersion").apply { isAccessible = true }
        val fieldDir = DocumentState::class.java.getDeclaredField("currentDirectory").apply { isAccessible = true }
        val fieldPages = DocumentState::class.java.getDeclaredField("currentPages").apply { isAccessible = true }
        val fieldLayers = DocumentState::class.java.getDeclaredField("currentLayers").apply { isAccessible = true }
        fieldVersion.set(copy, snap.version)
        fieldDir.set(copy, snap.directory)
        fieldPages.set(copy, snap.pages)
        fieldLayers.set(copy, snap.layers)
        return copy
    }
}
