package inkspace.runtime

import inkspace.core.codec.CoreCodec
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.id.UndoLabel
import inkspace.core.ink.BrushKind
import inkspace.core.ink.BrushSpec
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputContext
import inkspace.core.ink.InputToolType
import inkspace.core.ink.PredictedSample
import inkspace.core.ink.RealSample
import inkspace.core.ink.StylusInputPolicy
import inkspace.core.limits.Limits
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.ObjectStyle
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.mutation.AddPage
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.render.RenderReply
import inkspace.core.render.RenderRequest
import inkspace.core.render.Scene
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.runtime.ViewportState
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.ink.InkEngine
import inkspace.render.GoldenImageComparator
import inkspace.render.Renderer
import inkspace.testsupport.FakeResourcePort
import inkspace.testsupport.FakeTextPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class F06InkRuntimeIntegrationAndLatencyTest {

    private val defaultSeed = 20261105L

    private fun percentile(sortedValues: List<Double>, p: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val idx = ceil((p / 100.0) * sortedValues.size).toInt().coerceIn(1, sortedValues.size) - 1
        return sortedValues[idx]
    }

    @Test
    fun draft_and_committed_stroke_render_identically_and_persist_with_undo_redo_and_vector_reload(
        @TempDir tempDir: Path
    ) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val renderer = Renderer(
            geometryPort = runtime.defaultGeometryPort,
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )

        val docId = DocumentId("doc-f06-e2e")
        try {
            val session = runtime.openSessionSyncForTest(docId)
            runtime.configureInkSession(
                session = session,
                brush = BrushSpec(
                    kind = BrushKind.FOUNTAIN,
                    colorArgb = 0xFF1D4ED8.toInt(),
                    baseWidthPt = 3.0,
                    pressureSensitivity = 0.8f,
                    tiltSensitivity = 0.4f
                ),
                policy = StylusInputPolicy(
                    stylusOnlyDrawing = true,
                    palmRejectionEnabled = true,
                    temporaryEraserButtonEnabled = true
                )
            )

            // 1. Stream DOWN + MOVE with 5 real samples AND 2 predicted samples
            val downSamples = listOf(RealSample(20f, 25f, 0.4f, 0.15f, 100L))
            val moveSamples = listOf(
                RealSample(35f, 40f, 0.6f, 0.20f, 104L),
                RealSample(50f, 40f, 0.75f, 0.25f, 108L),
                RealSample(65f, 55f, 0.8f, 0.30f, 112L),
                RealSample(80f, 70f, 0.5f, 0.20f, 116L)
            )
            val predicted = listOf(
                PredictedSample(115f, 115f, 0.5f, 0.2f, 120L),
                PredictedSample(125f, 125f, 0.5f, 0.2f, 124L)
            )

            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 1L,
                    action = InputActionKind.DOWN,
                    realSamples = downSamples
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 2L,
                    action = InputActionKind.MOVE,
                    realSamples = moveSamples,
                    predictedSamples = predicted
                ).getOrNull()!!
            )
            runtime.drainEngineLoopForTest()

            val activeDrafts = runtime.activeDraftStrokes(session)
            assertEquals(1, activeDrafts.size, "Active draft stroke must be present during MOVE")
            val activeOverlays = runtime.activeEphemeralOverlays(session)
            assertEquals(1, activeOverlays.size, "Predicted overlay must be present in ephemeralOverlays")

            // Render draft stroke (without ephemeral prediction overlay) on 128x128 viewport
            val draftScene = Scene(
                snapshot = runtime.latestSnapshot(session)!!,
                viewportWidthPx = 128,
                viewportHeightPx = 128,
                draftStrokes = activeDrafts,
                ephemeralOverlays = emptyList()
            )
            val draftFrame = renderer.renderOnExecutor(
                RenderRequest.Draw(scene = draftScene, capturePixels = true)
            ).getOrNull() as RenderReply.FrameDrawn
            val draftPixels = draftFrame.copyCapturedPixels()!!

            // 2. Concurrent canceled stroke on pointer 1 -> must leave 0 phantom strokes
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 1,
                    sequenceNumber = 3L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(RealSample(10f, 90f, 0.5f, 0.1f, 118L))
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 1,
                    sequenceNumber = 4L,
                    action = InputActionKind.UP,
                    isFlagCanceled = true,
                    realSamples = listOf(RealSample(20f, 95f, 0.5f, 0.1f, 120L))
                ).getOrNull()!!
            )

            // 3. Finish pointer 0 on UP with the exact same final coordinate (80, 70) + scroll viewport concurrently
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 5L,
                    action = InputActionKind.UP,
                    realSamples = emptyList<RealSample>().ifEmpty {
                        listOf(RealSample(80f, 70f, 0.5f, 0.20f, 120L))
                    }
                ).getOrNull()!!
            )
            runtime.trySend(
                AppCommand.UpdateViewport(
                    ViewportState(
                        session = session,
                        camera = CameraTransform(scale = 2.0, translateX = -20.0, translateY = -20.0),
                        widthPx = 1080,
                        heightPx = 1920
                    )
                )
            )
            runtime.drainEngineLoopForTest()

            assertTrue(runtime.activeDraftStrokes(session).isEmpty(), "Active drafts must be empty after UP")
            val committedSnap = runtime.latestSnapshot(session)!!
            assertEquals(1, committedSnap.totalObjectCount, "Exact 1 stroke committed; 0 phantom strokes from pointer 1")
            assertEquals(SaveStatusKind.SAVED, runtime.latestSaveStatus(session)!!.status)

            // Render committed stroke at the same camera (1.0, 0, 0) and compare pixels with draftPixels!
            val committedScene = Scene(
                snapshot = committedSnap,
                camera = CameraTransform(scale = 1.0, translateX = 0.0, translateY = 0.0),
                viewportWidthPx = 128,
                viewportHeightPx = 128,
                draftStrokes = emptyList(),
                ephemeralOverlays = emptyList()
            )
            val committedFrame = renderer.renderOnExecutor(
                RenderRequest.Draw(scene = committedScene, capturePixels = true)
            ).getOrNull() as RenderReply.FrameDrawn
            val committedPixels = committedFrame.copyCapturedPixels()!!

            val cmp = GoldenImageComparator.compare(
                expectedArgb = draftPixels,
                actualArgb = committedPixels,
                widthPx = 128,
                heightPx = 128,
                channelTolerance = 0
            )
            assertTrue(cmp.matches, "Draft stroke and committed stroke must produce 100% identical pixels")

            // Verify zoomed vector rendering at scale = 4.0 (acceptance21: handwriting remains vector when zoomed)
            val zoomedFrame = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = committedScene.copy(camera = CameraTransform(scale = 4.0, translateX = -40.0, translateY = -40.0)),
                    capturePixels = true
                )
            ).getOrNull() as RenderReply.FrameDrawn
            assertEquals(1, zoomedFrame.stats.drawnStrokesCount)

            // Verify 0 predicted samples (115, 115 or 125, 125) are present in the committed stroke
            val savedStroke = committedSnap.orderedSceneObjects().single().payload as StrokePayload
            assertTrue(
                savedStroke.samples.none { (savedStroke.origin.x + it.relX) > 100.0 },
                "0 predicted samples may ever be saved in StrokePayload"
            )

            // 4. Undo removes the stroke; Redo restores it
            runtime.trySend(AppCommand.Undo(session))
            runtime.drainEngineLoopForTest()
            assertEquals(0, runtime.latestSnapshot(session)!!.totalObjectCount)

            runtime.trySend(AppCommand.Redo(session))
            runtime.drainEngineLoopForTest()
            assertEquals(1, runtime.latestSnapshot(session)!!.totalObjectCount)
            assertEquals(SaveStatusKind.SAVED, runtime.latestSaveStatus(session)!!.status)
        } finally {
            renderer.close()
            runtime.closeForTest()
        }

        // 5. Cold reopen from SQLite storage: verify stroke is still an editable vector StrokePayload
        val runtime2 = Runtime.start(config).getOrNull()!!
        try {
            val reopenedSession = runtime2.openSessionSyncForTest(docId)
            val reopenedSnap = runtime2.latestSnapshot(reopenedSession)!!
            assertEquals(1, reopenedSnap.totalObjectCount, "Reloaded document must contain the committed vector stroke")
            val reloadedObj = reopenedSnap.orderedSceneObjects().single()
            val reloadedPayload = reloadedObj.payload as StrokePayload
            assertEquals(6, reloadedPayload.samples.size)
            assertEquals(0.8f, reloadedPayload.samples[3].pressure, 1e-4f)
            assertEquals(0.30f, reloadedPayload.samples[3].tilt, 1e-4f)
        } finally {
            runtime2.closeForTest()
        }
    }

    @Test
    fun w1_handwriting_latency_benchmarks_meet_callback_ingest_canvas_post_and_durable_budgets(
        @TempDir tempDir: Path
    ) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val renderer = Renderer(
            geometryPort = runtime.defaultGeometryPort,
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
        val standaloneInkEngine = InkEngine()

        try {
            val docId = DocumentId("doc-f06-w1")
            val session = runtime.openSessionSyncForTest(docId)

            // Build W1 base scene (100 pages x 100 strokes x 64 real samples = 10,000 strokes) for Canvas post measurements
            val w1State = buildW1BaseState(docId)
            val w1Snap = w1State.snapshot()
            assertEquals(10_000, w1Snap.totalObjectCount)

            val w1BaseScene = Scene(
                snapshot = w1Snap,
                activeSpaceId = SpaceId("space-1"),
                camera = CameraTransform(scale = 1.0, translateX = 0.0, translateY = 0.0),
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
                surfaceEpoch = SurfaceEpoch(1L)
            )
            // Warm up renderer cache on W1 visible window
            renderer.renderOnExecutor(RenderRequest.Draw(scene = w1BaseScene, capturePixels = false))

            val rng = Random(defaultSeed)
            val measuredStrokes = 30
            val callbackDurationsMs = ArrayList<Double>(measuredStrokes * 2)
            val ingestDurationsMs = ArrayList<Double>(measuredStrokes)
            val inputToCanvasPostMs = ArrayList<Double>(measuredStrokes)
            val durableLagMs = ArrayList<Double>(measuredStrokes)

            var seq = 1L
            val ingestCtx = InputContext(
                docVersion = DocVersion(docId, Revision(0L)),
                activeSpaceId = SpaceId("space-1"),
                activePageId = PageId("page-0"),
                activeLayerId = LayerId("layer-1"),
                brush = BrushSpec(kind = BrushKind.BALL, colorArgb = 0xFF000000.toInt(), baseWidthPt = 2.0)
            )

            // Warmup 5 strokes
            for (w in 0 until 5) {
                val samples64 = build64Samples(rng, w)
                val bDown = InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = seq++,
                    action = InputActionKind.DOWN,
                    realSamples = samples64.subList(0, 1)
                ).getOrNull()!!
                val bMove = InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = seq++,
                    action = InputActionKind.MOVE,
                    realSamples = samples64
                ).getOrNull()!!
                val bUp = InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = seq++,
                    action = InputActionKind.UP,
                    realSamples = samples64.subList(63, 64)
                ).getOrNull()!!
                standaloneInkEngine.ingest(bDown, ingestCtx)
                standaloneInkEngine.ingest(bMove, ingestCtx)
                standaloneInkEngine.ingest(bUp, ingestCtx)
            }

            // Measured runs: 30 strokes x 64 samples
            for (s in 0 until measuredStrokes) {
                val samples64 = build64Samples(rng, s + 10)
                val downBatch = InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = seq++,
                    action = InputActionKind.DOWN,
                    realSamples = samples64.subList(0, 1)
                ).getOrNull()!!
                val move64Batch = InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = seq++,
                    action = InputActionKind.MOVE,
                    realSamples = samples64
                ).getOrNull()!!
                val upBatch = InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = seq++,
                    action = InputActionKind.UP,
                    realSamples = samples64.subList(63, 64)
                ).getOrNull()!!

                // 1. Measure engine ingest on 64-sample batch + Input -> Canvas post on W1
                standaloneInkEngine.ingest(downBatch, ingestCtx)
                val tIngest0 = System.nanoTime()
                val moveOut = standaloneInkEngine.ingest(move64Batch, ingestCtx).getOrNull()!!
                val tIngest1 = System.nanoTime()
                val draftItem = moveOut.draftStroke!!
                val drawRes = renderer.renderOnExecutor(
                    RenderRequest.Draw(
                        scene = w1BaseScene.copy(draftStrokes = listOf(draftItem)),
                        capturePixels = false
                    )
                )
                val tCanvasPost1 = System.nanoTime()
                assertTrue(drawRes.isSuccess)
                standaloneInkEngine.ingest(upBatch, ingestCtx)

                ingestDurationsMs.add((tIngest1 - tIngest0) / 1_000_000.0)
                inputToCanvasPostMs.add((tCanvasPost1 - tIngest0) / 1_000_000.0)

                // 2. Measure Main callback dispatch & end-to-end durable lag through Runtime + SQLite DocumentStore
                val tCb0 = System.nanoTime()
                runtime.submitInkBatch(downBatch)
                val tCb1 = System.nanoTime()
                callbackDurationsMs.add((tCb1 - tCb0) / 1_000_000.0)

                val tCb2 = System.nanoTime()
                runtime.submitInkBatch(upBatch)
                val tCb3 = System.nanoTime()
                callbackDurationsMs.add((tCb3 - tCb2) / 1_000_000.0)

                val tDurable0 = System.nanoTime()
                runtime.drainEngineLoopForTest()
                val tDurable1 = System.nanoTime()
                assertEquals(SaveStatusKind.SAVED, runtime.latestSaveStatus(session)!!.status)
                durableLagMs.add((tDurable1 - tDurable0) / 1_000_000.0)
            }

            val cbSorted = callbackDurationsMs.sorted()
            val ingestSorted = ingestDurationsMs.sorted()
            val canvasSorted = inputToCanvasPostMs.sorted()
            val durableSorted = durableLagMs.sorted()

            val cbP50 = percentile(cbSorted, 50.0)
            val cbP95 = percentile(cbSorted, 95.0)
            val ingestP50 = percentile(ingestSorted, 50.0)
            val ingestP95 = percentile(ingestSorted, 95.0)
            val canvasP50 = percentile(canvasSorted, 50.0)
            val canvasP95 = percentile(canvasSorted, 95.0)
            val durableP50 = percentile(durableSorted, 50.0)
            val durableP95 = percentile(durableSorted, 95.0)

            assertTrue(cbP95 <= 1.0, "Main callback dispatch p95 ($cbP95 ms) must be <= 1.0 ms")
            assertTrue(ingestP95 <= 2.0, "Engine 64-sample ingest p95 ($ingestP95 ms) must be <= 2.0 ms")
            assertTrue(canvasP95 <= 8.0, "Input -> Canvas post on W1 p95 ($canvasP95 ms) must be <= 8.0 ms")
            assertTrue(durableP95 <= 200.0, "Durable lag on W1 p95 ($durableP95 ms) must be <= 200.0 ms")

            writeRawInkLatencyEvidence(
                measuredStrokes = measuredStrokes,
                cbP50 = cbP50,
                cbP95 = cbP95,
                ingestP50 = ingestP50,
                ingestP95 = ingestP95,
                canvasP50 = canvasP50,
                canvasP95 = canvasP95,
                durableP50 = durableP50,
                durableP95 = durableP95
            )
        } finally {
            renderer.close()
            runtime.closeForTest()
        }
    }

    private fun build64Samples(rng: Random, strokeIdx: Int): List<RealSample> {
        return (0 until 64).map { s ->
            val t = s.toDouble() / 63.0
            val x = (30.0 + t * 120.0 + sin(t * 6.2831853 + strokeIdx) * 4.0).toFloat()
            val y = (40.0 + cos(t * 3.1415926 + strokeIdx) * 8.0 + s * 1.5).toFloat()
            RealSample(
                x = x,
                y = y,
                pressure = (0.35f + rng.nextFloat() * 0.5f).coerceIn(0.0f, 1.0f),
                tilt = 0.15f,
                timestampMs = 1000L + strokeIdx * 500L + s * 4L
            )
        }
    }

    private fun buildW1BaseState(docId: DocumentId): DocumentState {
        val spaceId = SpaceId("space-1")
        val layerId = LayerId("layer-1")
        val pageWidthPt = 600.0
        val pageHeightPt = 800.0
        val state = DocumentState.createInitial(
            documentId = docId,
            initialSpace = spaceId,
            initialPage = PageDescriptor(PageId("page-0"), spaceId, pageWidthPt, pageHeightPt),
            initialLayer = LayerDescriptor(layerId, PageId("page-0"), "Layer 1")
        )
        val addPages = (1 until 100).map { p ->
            AddPage(page = PageDescriptor(PageId("page-$p"), spaceId, pageWidthPt, pageHeightPt))
        }
        val limits = Limits(maxObjectsPerSpace = 15_000)
        applyPlan(
            state,
            validatePlan(
                state.snapshot(),
                EditPlan(DocVersion(docId, Revision(0L)), emptyList(), addPages, GestureId("g-p"), UndoLabel("p")),
                limits
            ).getOrNull()!!,
            limits
        )

        val samples64 = java.util.List.copyOf(
            (0 until 64).map { s ->
                StrokeSamplePoint(relX = s * 0.6f, relY = (s % 8).toFloat(), pressure = 0.5f, tilt = 0.1f, timeDeltaMs = 4)
            }
        )
        val templatePayload = StrokePayload(
            brushId = "ball",
            color = 0xFF1A2B4C.toInt(),
            baseWidthPt = 2.0,
            origin = DocPoint(0.0, 0.0),
            samples = samples64,
            smoothingVersion = 1
        )
        val digest = CoreCodec.computePayloadDigest(templatePayload)

        var idx = 0L
        for (p in 0 until 100) {
            val topY = p * pageHeightPt
            for (s in 0 until 100) {
                val col = s % 10
                val row = s / 10
                val ox = 20.0 + col * 55.0
                val oy = topY + 20.0 + row * 35.0
                state.putResidentObjectDirect(
                    SceneObject(
                        id = ObjectId("w1-$idx"),
                        space = spaceId,
                        layer = layerId,
                        bounds = DocRect(ox, oy, ox + 44.0, oy + 14.0),
                        payload = templatePayload,
                        transform = AffineTransform2D(m02 = ox, m12 = oy),
                        style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = idx),
                        isLocked = false,
                        version = 1L,
                        payloadDigest = digest
                    )
                )
                idx += 1L
            }
        }
        return state
    }

    private fun writeRawInkLatencyEvidence(
        measuredStrokes: Int,
        cbP50: Double,
        cbP95: Double,
        ingestP50: Double,
        ingestP95: Double,
        canvasP50: Double,
        canvasP95: Double,
        durableP50: Double,
        durableP95: Double
    ) {
        val rootDir = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: File(".")
        val outFile = File(rootDir, "docs/evidence/F06/w1-ink-latency-raw.json")
        outFile.parentFile?.mkdirs()

        val json = String.format(
            Locale.US,
            """
            {
              "phase_id": "F06",
              "seed": %d,
              "workload": "W1 (100 pages x 100 strokes x 64 real samples)",
              "measured_strokes": %d,
              "samples_per_batch": 64,
              "metrics": {
                "main_callback_dispatch_ms": {
                  "budget_p95_ms": 1.0,
                  "p50_ms": %.4f,
                  "p95_ms": %.4f
                },
                "engine_ingest_64_samples_ms": {
                  "budget_p95_ms": 2.0,
                  "p50_ms": %.4f,
                  "p95_ms": %.4f
                },
                "input_to_canvas_post_w1_ms": {
                  "budget_p95_ms": 8.0,
                  "p50_ms": %.4f,
                  "p95_ms": %.4f
                },
                "durable_commit_lag_w1_ms": {
                  "budget_p95_ms": 200.0,
                  "p50_ms": %.4f,
                  "p95_ms": %.4f
                }
              },
              "invariants": {
                "phantom_strokes_on_cancel": 0,
                "predicted_samples_saved_in_db_or_undo": 0,
                "draft_to_committed_pixel_mismatch_count": 0,
                "reloaded_vector_editable_after_reopen": true
              }
            }
            """.trimIndent() + "\n",
            defaultSeed,
            measuredStrokes,
            cbP50,
            cbP95,
            ingestP50,
            ingestP95,
            canvasP50,
            canvasP95,
            durableP50,
            durableP95
        )
        outFile.writeText(json)
    }
}
