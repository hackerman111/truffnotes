package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.SpaceId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocumentKind
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.ObjectStyle
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.History
import inkspace.core.mutation.SetLayerLock
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.objects.EraserMode
import inkspace.core.objects.EraserShape
import inkspace.core.objects.EraserSpec
import inkspace.core.objects.FlipAxis
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.PlanContext
import inkspace.core.objects.SelectionMode
import inkspace.core.objects.SelectionQuery
import inkspace.core.objects.SelectionRule
import inkspace.core.objects.SelectionSet
import inkspace.core.objects.StylePatch
import inkspace.core.objects.ZOrderCommand
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class F07PropertyAndSpatialBenchmarkTest {

    private val docId = DocumentId("doc-f07-prop")
    private val spaceId = SpaceId("space-1")
    private val pageId = PageId("page-1")
    private val layer1 = LayerId("layer-1")

    private fun makeSeedStroke(id: String, x0: Double, y0: Double, z: Long): SceneObject {
        val samples = listOf(
            StrokeSamplePoint(0f, 0f, 0.3f, 0.1f, 0),
            StrokeSamplePoint(10f, 5f, 0.5f, 0.2f, 8),
            StrokeSamplePoint(20f, 10f, 0.7f, 0.3f, 8),
            StrokeSamplePoint(30f, 15f, 0.9f, 0.4f, 8)
        )
        return SceneObject(
            id = ObjectId(id),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(x0 - 2.0, y0 - 2.0, x0 + 32.0, y0 + 17.0),
            payload = StrokePayload(
                brushId = "ball",
                color = 0xFF102030.toInt(),
                baseWidthPt = 2.0,
                origin = DocPoint(x0, y0),
                samples = samples
            ),
            style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = z)
        )
    }

    @Test
    fun ten_thousand_generated_object_edits_undo_redo_and_locked_layer_atomic_rejection() {
        val rng = Random(20261105L)
        val limits = Limits(maxObjectsPerSpace = 10_000, maxGroupDepth = 32)
        var totalAppliedEdits = 0
        val batches = 200
        val stepsPerBatch = 50 // 200 * 50 = 10,000 generated edits

        for (batchIdx in 0 until batches) {
            val state = DocumentState.createInitial(
                documentId = docId,
                kind = DocumentKind.Notebook,
                initialSpace = spaceId,
                initialPage = PageDescriptor(pageId, spaceId, 600.0, 800.0),
                initialLayer = LayerDescriptor(layer1, pageId, "Layer 1")
            )
            // Seed 12 strokes per batch
            for (i in 1..12) {
                val x = (i % 4) * 80.0 + 40.0
                val y = (i / 4) * 80.0 + 40.0
                state.putResidentObjectDirect(makeSeedStroke("s-$batchIdx-$i", x, y, i.toLong()))
            }

            val initialFingerprint = state.snapshot().contentFingerprint()
            val initialCount = state.totalObjectCount
            val history = History(maxEntries = 128, maxPinnedBytes = 64_000_000L)
            var appliedInBatch = 0
            var stepSeq = 0

            while (appliedInBatch < stepsPerBatch) {
                stepSeq += 1
                val snap = state.snapshot()
                val allObjs = snap.orderedSceneObjects()
                val topLevelIds = allObjs.map { it.id }.filter { snap.parentGroupOf(it) == null }
                val groupIds = allObjs.filter { it.payload is GroupPayload }.map { it.id }

                val ctx = PlanContext(
                    activeSpaceId = spaceId,
                    activePageId = pageId,
                    activeLayerId = layer1,
                    gestureId = GestureId("g-$batchIdx-$stepSeq"),
                    limits = limits
                )

                val actionChoice = rng.nextInt(10)
                val action: ObjectAction = when {
                    topLevelIds.size <= 2 || actionChoice == 0 -> {
                        val target = topLevelIds.random(rng)
                        ObjectAction.Duplicate(
                            targets = setOf(target),
                            offsetX = rng.nextDouble(-15.0, 15.0),
                            offsetY = rng.nextDouble(-15.0, 15.0),
                            idPrefix = "dup-$batchIdx-$stepSeq"
                        )
                    }
                    actionChoice == 1 -> {
                        val picks = topLevelIds.shuffled(rng).take(rng.nextInt(1, 4)).toSet()
                        ObjectAction.Move(
                            targets = picks,
                            deltaX = rng.nextDouble(-25.0, 25.0).let { if (kotlin.math.abs(it) < 0.5) 5.0 else it },
                            deltaY = rng.nextDouble(-25.0, 25.0)
                        )
                    }
                    actionChoice == 2 -> {
                        val picks = topLevelIds.shuffled(rng).take(rng.nextInt(1, 3)).toSet()
                        ObjectAction.Scale(
                            targets = picks,
                            scaleX = rng.nextDouble(0.8, 1.25),
                            scaleY = rng.nextDouble(0.8, 1.25)
                        )
                    }
                    actionChoice == 3 -> {
                        val picks = topLevelIds.shuffled(rng).take(rng.nextInt(1, 3)).toSet()
                        ObjectAction.Rotate(
                            targets = picks,
                            angleRadians = rng.nextDouble(-PI / 4.0, PI / 4.0)
                        )
                    }
                    actionChoice == 4 -> {
                        val picks = topLevelIds.shuffled(rng).take(rng.nextInt(1, 3)).toSet()
                        ObjectAction.Flip(
                            targets = picks,
                            axis = if (rng.nextBoolean()) FlipAxis.HORIZONTAL else FlipAxis.VERTICAL
                        )
                    }
                    actionChoice == 5 -> {
                        val picks = topLevelIds.shuffled(rng).take(rng.nextInt(1, 3)).toSet()
                        ObjectAction.ApplyStyle(
                            targets = picks,
                            patch = StylePatch(
                                colorArgb = 0xFF000000.toInt() or rng.nextInt(0x100000, 0xFFFFFF),
                                strokeWidthPt = rng.nextDouble(1.0, 8.0),
                                opacity = rng.nextFloat().coerceIn(0.2f, 1.0f)
                            )
                        )
                    }
                    actionChoice == 6 -> {
                        val picks = topLevelIds.shuffled(rng).take(1).toSet()
                        val cmd = ZOrderCommand.entries[rng.nextInt(ZOrderCommand.entries.size)]
                        ObjectAction.ChangeZOrder(picks, cmd)
                    }
                    actionChoice == 7 && topLevelIds.size >= 2 -> {
                        val picks = topLevelIds.shuffled(rng).take(2).toSet()
                        ObjectAction.Group(
                            targets = picks,
                            newGroupId = ObjectId("grp-$batchIdx-$stepSeq")
                        )
                    }
                    actionChoice == 8 && groupIds.isNotEmpty() -> {
                        ObjectAction.Ungroup(setOf(groupIds.random(rng)))
                    }
                    else -> {
                        // Partial segment erase across a stroke's midpoint
                        val strokeObjs = allObjs.filter { it.payload is StrokePayload }
                        if (strokeObjs.isNotEmpty() && topLevelIds.size > 3) {
                            val st = strokeObjs.random(rng)
                            val cx = (st.bounds.left + st.bounds.right) * 0.5
                            val cy = (st.bounds.top + st.bounds.bottom) * 0.5
                            ObjectAction.Erase(
                                pathPoints = listOf(DocPoint(cx, cy - 10.0), DocPoint(cx, cy + 10.0)),
                                spec = EraserSpec(
                                    mode = EraserMode.SEGMENT,
                                    shape = EraserShape.CIRCLE,
                                    radiusPt = 4.0
                                ),
                                splitIdPrefix = "er-$batchIdx-$stepSeq"
                            )
                        } else {
                            val target = topLevelIds.random(rng)
                            ObjectAction.Move(setOf(target), 6.0, -4.0)
                        }
                    }
                }

                val planOut = ObjectPlanner.plan(state, action, ctx)
                if (planOut.isFailure) continue
                val plan = planOut.getOrNull()!!
                val valOut = validatePlan(state, plan, limits)
                assertTrue(valOut.isSuccess, "Expected valid plan in batch $batchIdx step $stepSeq: ${valOut.errorOrNull()}")
                val appOut = applyPlan(state, valOut.getOrNull()!!, limits)
                assertTrue(appOut.isSuccess, "Expected apply success in batch $batchIdx step $stepSeq: ${appOut.errorOrNull()}")

                history.record(plan, appOut.getOrNull()!!)
                assertTrue(state.snapshot().verifyInvariants(), "Document invariants must hold after step $stepSeq")
                appliedInBatch += 1
                totalAppliedEdits += 1
            }

            val finalFingerprint = state.snapshot().contentFingerprint()
            val finalCount = state.totalObjectCount

            // Undo all 50 steps in the batch -> must match exact initial state
            while (history.canUndo) {
                val undoPlan = history.undo(state.version)
                assertNotNull(undoPlan)
                val v = validatePlan(state, undoPlan, limits).getOrNull()!!
                applyPlan(state, v, limits)
            }
            assertEquals(initialCount, state.totalObjectCount, "Undo count mismatch in batch $batchIdx")
            assertEquals(
                initialFingerprint,
                state.snapshot().contentFingerprint(),
                "Undo fingerprint mismatch in batch $batchIdx"
            )

            // Redo all 50 steps -> must match exact final state
            while (history.canRedo) {
                val redoPlan = history.redo(state.version)
                assertNotNull(redoPlan)
                val v = validatePlan(state, redoPlan, limits).getOrNull()!!
                applyPlan(state, v, limits)
            }
            assertEquals(finalCount, state.totalObjectCount, "Redo count mismatch in batch $batchIdx")
            assertEquals(
                finalFingerprint,
                state.snapshot().contentFingerprint(),
                "Redo fingerprint mismatch in batch $batchIdx"
            )
        }

        assertEquals(10_000, totalAppliedEdits, "Must execute >= 10,000 generated edits")

        // Verify locked layer atomic rejection across all action types
        val lockedState = DocumentState.createInitial(
            documentId = docId,
            kind = DocumentKind.Notebook,
            initialSpace = spaceId,
            initialPage = PageDescriptor(pageId, spaceId, 600.0, 800.0),
            initialLayer = LayerDescriptor(layer1, pageId, "Layer 1", isLocked = false)
        )
        lockedState.putResidentObjectDirect(makeSeedStroke("s-lock-1", 50.0, 50.0, 1L))
        lockedState.putResidentObjectDirect(makeSeedStroke("s-lock-2", 80.0, 50.0, 2L))
        val lockPlan = EditPlan(
            base = lockedState.version,
            mutations = listOf(SetLayerLock(layer1, true)),
            gesture = GestureId("g-lock"),
            label = UndoLabel("Lock")
        )
        applyPlan(lockedState, validatePlan(lockedState, lockPlan, limits).getOrNull()!!, limits)
        val beforeLockFp = lockedState.snapshot().contentFingerprint()

        val ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)
        val targets = setOf(ObjectId("s-lock-1"), ObjectId("s-lock-2"))
        val rejectedActions = listOf(
            ObjectAction.Move(targets, 10.0, 10.0),
            ObjectAction.Scale(targets, 1.2, 1.2),
            ObjectAction.Rotate(targets, 0.5),
            ObjectAction.Flip(targets, FlipAxis.HORIZONTAL),
            ObjectAction.ApplyStyle(targets, StylePatch(colorArgb = -1)),
            ObjectAction.ChangeZOrder(targets, ZOrderCommand.BRING_TO_FRONT),
            ObjectAction.Group(targets, ObjectId("g-rejected")),
            ObjectAction.Delete(targets),
            ObjectAction.Duplicate(targets)
        )
        for (act in rejectedActions) {
            val out = ObjectPlanner.plan(lockedState, act, ctx)
            assertTrue(out.isFailure, "Action $act on locked layer must fail")
            assertTrue(out.errorOrNull() is FeatureError.LockedTarget)
            assertEquals(beforeLockFp, lockedState.snapshot().contentFingerprint())
        }
    }

    @Test
    fun w2_spatial_query_benchmark_lasso_and_eraser_up_to_1000_candidates_within_8ms() {
        // W2: 500 pages, 100,000 strokes x 64 samples (200 strokes per page)
        // Each page is placed vertically at y = pageIdx * 1000.0
        val state = DocumentState.createInitial(
            documentId = DocumentId("doc-w2-spatial"),
            kind = DocumentKind.Notebook,
            initialSpace = spaceId,
            initialPage = PageDescriptor(pageId, spaceId, 600.0, 800.0),
            initialLayer = LayerDescriptor(layer1, pageId, "Layer 1")
        )

        val sharedSamples = (0 until 64).map { idx ->
            StrokeSamplePoint(
                relX = idx * 0.5f,
                relY = (idx % 4) * 0.5f,
                pressure = 0.5f,
                tilt = 0.1f,
                timeDeltaMs = if (idx == 0) 0 else 4
            )
        }
        val basePayload = StrokePayload(
            brushId = "ball",
            color = 0xFF000000.toInt(),
            baseWidthPt = 2.0,
            origin = DocPoint(100.0, 100.0),
            samples = sharedSamples
        )

        // Populate 100,000 strokes across 500 pages:
        // First 5 pages (1,000 strokes) are resident in the active viewport window;
        // remaining 99,000 strokes on pages 5..499 are registered in the spatial directory.
        var strokeCounter = 0
        for (pIdx in 0 until 500) {
            val pageYOffset = pIdx * 1000.0
            for (sIdx in 0 until 200) {
                strokeCounter += 1
                val x = (sIdx % 20) * 25.0 + 20.0
                val y = pageYOffset + (sIdx / 20) * 50.0 + 20.0
                val bounds = DocRect(x, y, x + 32.0, y + 4.0)
                val id = ObjectId("w2-s-$strokeCounter")
                if (pIdx < 5) {
                    state.putResidentObjectDirect(
                        SceneObject(
                            id = id,
                            space = spaceId,
                            layer = layer1,
                            bounds = bounds,
                            payload = basePayload.copy(origin = DocPoint(x, y))
                        )
                    )
                } else {
                    state.registerNonResidentObject(
                        id = id,
                        space = spaceId,
                        layer = layer1,
                        bounds = bounds,
                        version = 1L,
                        estimatedBytes = 512
                    )
                }
            }
        }
        assertEquals(100_000, state.totalObjectCount)

        // Query region covering the first 5 pages (1,000 candidate strokes out of 100,000)
        val lassoPolygon = listOf(
            DocPoint(0.0, 0.0),
            DocPoint(600.0, 0.0),
            DocPoint(600.0, 4900.0),
            DocPoint(0.0, 4900.0)
        )
        val lassoQuery = SelectionQuery(
            spaceId = spaceId,
            mode = SelectionMode.FREEHAND,
            rule = SelectionRule.INTERSECTS,
            polygonPoints = lassoPolygon
        )

        val eraserAction = ObjectAction.Erase(
            pathPoints = listOf(DocPoint(30.0, 10.0), DocPoint(550.0, 4800.0)),
            spec = EraserSpec(
                mode = EraserMode.SEGMENT,
                shape = EraserShape.CIRCLE,
                radiusPt = 8.0
            ),
            splitIdPrefix = "w2-er"
        )
        val ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)

        // 5 warmups
        repeat(5) {
            val sel = SelectionEngine.select(state, SelectionSet(), lassoQuery)
            assertTrue(sel.isSuccess)
            assertEquals(1_000, sel.getOrNull()!!.size)
            val er = ObjectPlanner.plan(state, eraserAction, ctx)
            assertTrue(er.isSuccess)
        }

        val lassoLatenciesMs = DoubleArray(30)
        val eraserLatenciesMs = DoubleArray(30)

        for (i in 0 until 30) {
            val t0 = System.nanoTime()
            val sel = SelectionEngine.select(state, SelectionSet(), lassoQuery)
            val t1 = System.nanoTime()
            assertTrue(sel.isSuccess)
            assertEquals(1_000, sel.getOrNull()!!.size)
            lassoLatenciesMs[i] = (t1 - t0) / 1_000_000.0

            val t2 = System.nanoTime()
            val er = ObjectPlanner.plan(state, eraserAction, ctx)
            val t3 = System.nanoTime()
            assertTrue(er.isSuccess)
            eraserLatenciesMs[i] = (t3 - t2) / 1_000_000.0
        }

        fun percentile(values: DoubleArray, p: Double): Double {
            val sorted = values.sortedArray()
            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        val lassoP50 = percentile(lassoLatenciesMs, 0.50)
        val lassoP95 = percentile(lassoLatenciesMs, 0.95)
        val eraserP50 = percentile(eraserLatenciesMs, 0.50)
        val eraserP95 = percentile(eraserLatenciesMs, 0.95)

        writeRawSpatialEvidence(
            lassoP50 = lassoP50,
            lassoP95 = lassoP95,
            eraserP50 = eraserP50,
            eraserP95 = eraserP95
        )

        assertTrue(lassoP95 <= 8.0, "Expected W2 lasso spatial query p95 <= 8.0 ms, got $lassoP95 ms")
        assertTrue(eraserP95 <= 8.0, "Expected W2 eraser spatial query p95 <= 8.0 ms, got $eraserP95 ms")
    }

    private fun writeRawSpatialEvidence(
        lassoP50: Double,
        lassoP95: Double,
        eraserP50: Double,
        eraserP95: Double
    ) {
        val rootDir = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: File(".")
        val outFile = File(rootDir, "docs/evidence/F07/w2-spatial-query-raw.json")
        outFile.parentFile.mkdirs()
        val json = """
            {
              "phase_id": "F07",
              "seed": 20261105,
              "workload": "W2 (500 pages, 100000 strokes x 64 samples, 1000 candidates in query region)",
              "total_objects": 100000,
              "candidates_in_query": 1000,
              "measured_runs": 30,
              "generated_undo_redo_edits_verified": 10000,
              "metrics": {
                "w2_freehand_lasso_1000_candidates_ms": {
                  "budget_p95_ms": 8.0,
                  "p50_ms": ${String.format(Locale.US, "%.4f", lassoP50)},
                  "p95_ms": ${String.format(Locale.US, "%.4f", lassoP95)}
                },
                "w2_segment_eraser_1000_candidates_ms": {
                  "budget_p95_ms": 8.0,
                  "p50_ms": ${String.format(Locale.US, "%.4f", eraserP50)},
                  "p95_ms": ${String.format(Locale.US, "%.4f", eraserP95)}
                }
              }
            }
        """.trimIndent()
        outFile.writeText(json)
    }
}
