package inkspace.ink

import inkspace.core.error.InputError
import inkspace.core.id.DocumentId
import inkspace.core.id.LayerId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.SpaceId
import inkspace.core.ink.BrushKind
import inkspace.core.ink.BrushSpec
import inkspace.core.ink.EffectiveInkToolMode
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputContext
import inkspace.core.ink.InputToolType
import inkspace.core.ink.PredictedSample
import inkspace.core.ink.RealSample
import inkspace.core.ink.StrokeEndCap
import inkspace.core.ink.StrokeLinePattern
import inkspace.core.ink.StylusInputPolicy
import inkspace.core.limits.Limits
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentState
import inkspace.core.model.SessionKey
import inkspace.core.mutation.History
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.StrokePayload
import inkspace.core.ports.GeometryRequest
import inkspace.core.runtime.InputActionKind
import inkspace.core.unit.DocPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class F06ReplayFixturesAndBrushesTest {

    private val docId = DocumentId("doc-f06-ink")
    private val session = SessionKey(docId, SessionEpoch(1L))
    private val baseCtx = InputContext(
        docVersion = DocVersion(docId, Revision(0L)),
        activeSpaceId = SpaceId("space-1"),
        activePageId = PageId("page-1"),
        activeLayerId = LayerId("layer-1")
    )

    @Test fun palm_posture_applies_to_recent_pen_and_active_pen_always_wins() {
        val finger = InputBatch.fromSamples(session = session, pointerId = 2, sequenceNumber = 2L,
            action = InputActionKind.DOWN, toolType = InputToolType.FINGER,
            realSamples = listOf(RealSample(20f, 10f, .5f, 0f, 110L))).getOrNull()!!
        val ctx = baseCtx.copy(policy = StylusInputPolicy(palmPosture = inkspace.core.ink.PalmPosture.LEFT_OF_PEN))
        assertEquals(EffectiveInkToolMode.DRAW, InkToolArbiter.classifyToolMode(finger, ctx, false, 100L, 10f to 10f))
        assertEquals(EffectiveInkToolMode.PALM_REJECTED, InkToolArbiter.classifyToolMode(finger, ctx, true, 100L, 10f to 10f))
        assertEquals(EffectiveInkToolMode.PALM_REJECTED, InkToolArbiter.classifyToolMode(finger, ctx.copy(policy = ctx.policy.copy(palmPosture = inkspace.core.ink.PalmPosture.RIGHT_OF_PEN)), false, 100L, 10f to 10f))
    }

    @Test
    fun historical_points_and_prediction_replaced_preserve_only_real_samples_in_exact_order() {
        val engine = InkEngine()

        // 1. DOWN with 1 real sample
        val downBatch = InputBatch.fromSamples(
            session = session,
            pointerId = 0,
            sequenceNumber = 1L,
            action = InputActionKind.DOWN,
            realSamples = listOf(RealSample(10f, 20f, 0.4f, 0.1f, 100L))
        ).getOrNull()!!
        val downOut = engine.ingest(downBatch, baseCtx).getOrNull()!!
        assertNotNull(downOut.draftStroke)
        assertNull(downOut.commitPlan)
        assertEquals(1, downOut.totalRealSamplesIngested)

        // 2. MOVE with 3 historical + 1 current RealSample AND 2 PredictedSamples
        val moveBatch = InputBatch.fromSamples(
            session = session,
            pointerId = 0,
            sequenceNumber = 2L,
            action = InputActionKind.MOVE,
            realSamples = listOf(
                RealSample(12f, 22f, 0.5f, 0.15f, 104L),
                RealSample(15f, 25f, 0.6f, 0.20f, 108L),
                RealSample(19f, 29f, 0.7f, 0.25f, 112L),
                RealSample(24f, 34f, 0.75f, 0.30f, 116L)
            ),
            predictedSamples = listOf(
                PredictedSample(999f, 888f, 0.9f, 0.3f, 120L),
                PredictedSample(1111f, 2222f, 0.95f, 0.3f, 124L)
            )
        ).getOrNull()!!
        val moveOut = engine.ingest(moveBatch, baseCtx).getOrNull()!!
        assertNotNull(moveOut.draftStroke)
        assertNotNull(moveOut.predictedOverlay, "Predicted samples must produce an ephemeral prediction overlay")
        assertEquals(5, moveOut.totalRealSamplesIngested)
        // Draft stroke payload itself must never contain predicted coordinates (999, 888)
        assertTrue(moveOut.draftStroke!!.payload.samples.none { it.relX > 500f || it.relY > 500f })

        // 3. UP with 1 final real sample (replaces previous prediction, commits only the 6 real samples)
        val upBatch = InputBatch.fromSamples(
            session = session,
            pointerId = 0,
            sequenceNumber = 3L,
            action = InputActionKind.UP,
            realSamples = listOf(RealSample(30f, 40f, 0.5f, 0.2f, 120L))
        ).getOrNull()!!
        val upOut = engine.ingest(upBatch, baseCtx).getOrNull()!!
        assertNull(upOut.draftStroke, "Completed stroke clears active draft")
        assertNull(upOut.predictedOverlay, "Completed stroke clears prediction overlay")
        val plan = upOut.commitPlan
        assertNotNull(plan)
        assertEquals(1, plan!!.mutations.size)

        val insert = plan.mutations.single() as InsertObject
        val stroke = insert.payload as StrokePayload
        assertEquals(6, stroke.samples.size, "Exact 6 real samples (historical + current) must be committed; 0 predicted")

        val expectedXs = listOf(10.0, 12.0, 15.0, 19.0, 24.0, 30.0)
        val expectedYs = listOf(20.0, 22.0, 25.0, 29.0, 34.0, 40.0)
        for (i in stroke.samples.indices) {
            val worldX = stroke.origin.x + stroke.samples[i].relX
            val worldY = stroke.origin.y + stroke.samples[i].relY
            assertEquals(expectedXs[i], worldX, 1e-3)
            assertEquals(expectedYs[i], worldY, 1e-3)
        }
    }

    @Test
    fun pointer_reuse_cancel_stream_flag_canceled_and_mixed_finger_pen_produce_zero_phantom_strokes() {
        val spool = InMemoryStrokeSpoolSink()
        val engine = InkEngine(spoolSink = spool)

        // 1. cancel_stream: pointer 0 starts drawing and receives ACTION_CANCEL -> 0 phantom strokes
        engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 1L,
                action = InputActionKind.DOWN,
                realSamples = listOf(RealSample(10f, 10f, 0.5f, 0f, 10L))
            ).getOrNull()!!,
            baseCtx
        )
        val cancelOut = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 2L,
                action = InputActionKind.CANCEL,
                realSamples = listOf(RealSample(15f, 15f, 0.5f, 0f, 14L))
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        assertEquals(EffectiveInkToolMode.CANCELED, cancelOut.effectiveToolMode)
        assertNull(cancelOut.draftStroke)
        assertNull(cancelOut.commitPlan)
        assertEquals(0L, cancelOut.residentWindowBytes)

        // 2. pointer_reuse: OS reuses pointerId=0 for two consecutive valid strokes
        val down1 = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 3L,
                action = InputActionKind.DOWN,
                realSamples = listOf(RealSample(20f, 20f, 0.5f, 0f, 20L))
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        val up1 = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 4L,
                action = InputActionKind.UP,
                realSamples = listOf(
                    RealSample(25f, 25f, 0.6f, 0f, 24L),
                    RealSample(30f, 30f, 0.6f, 0f, 28L)
                )
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        assertNotNull(up1.commitPlan)

        val down2 = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 5L,
                action = InputActionKind.DOWN,
                realSamples = listOf(RealSample(100f, 100f, 0.5f, 0f, 40L))
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        assertNotEquals(down1.strokeEpoch, down2.strokeEpoch, "Reused pointerId must receive a new strokeEpoch")
        val up2 = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 6L,
                action = InputActionKind.UP,
                realSamples = listOf(
                    RealSample(110f, 110f, 0.5f, 0f, 44L),
                    RealSample(120f, 120f, 0.5f, 0f, 48L)
                )
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        assertNotNull(up2.commitPlan)
        val objId1 = (up1.commitPlan!!.mutations.single() as InsertObject).id
        val objId2 = (up2.commitPlan!!.mutations.single() as InsertObject).id
        assertNotEquals(objId1, objId2, "Reused pointerId must never overwrite previous stroke ObjectId")

        // 3. mixed_finger_pen + flag_canceled:
        // Active stylus on pointer 1 starts drawing; palm/finger on pointer 2 touches down and gets palm-rejected / flag_canceled.
        // Stylus stroke on pointer 1 must complete intact!
        engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 1,
                sequenceNumber = 7L,
                action = InputActionKind.DOWN,
                toolType = InputToolType.STYLUS,
                realSamples = listOf(RealSample(50f, 50f, 0.7f, 0.2f, 100L))
            ).getOrNull()!!,
            baseCtx
        )
        val palmTouch = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 2,
                sequenceNumber = 8L,
                action = InputActionKind.DOWN,
                toolType = InputToolType.FINGER,
                supportsPressure = false,
                supportsTilt = false,
                realSamples = listOf(RealSample(300f, 300f, 0.5f, 0f, 104L))
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        assertEquals(EffectiveInkToolMode.PALM_REJECTED, palmTouch.effectiveToolMode)
        assertNull(palmTouch.commitPlan)

        // Finger pointer 2 receives FLAG_CANCELED on UP
        val flagCancelTouch = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 2,
                sequenceNumber = 9L,
                action = InputActionKind.UP,
                toolType = InputToolType.FINGER,
                isFlagCanceled = true,
                realSamples = listOf(RealSample(302f, 302f, 0.5f, 0f, 108L))
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        assertNull(flagCancelTouch.commitPlan)

        // Stylus pointer 1 finishes normally -> committed intact!
        val stylusUp = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 1,
                sequenceNumber = 10L,
                action = InputActionKind.UP,
                toolType = InputToolType.STYLUS,
                realSamples = listOf(
                    RealSample(55f, 55f, 0.75f, 0.2f, 110L),
                    RealSample(60f, 60f, 0.8f, 0.2f, 114L),
                    RealSample(65f, 65f, 0.6f, 0.2f, 118L)
                )
            ).getOrNull()!!,
            baseCtx
        ).getOrNull()!!
        assertNotNull(stylusUp.commitPlan)
        val stylusStroke = (stylusUp.commitPlan!!.mutations.single() as InsertObject).payload as StrokePayload
        assertEquals(4, stylusStroke.samples.size)
    }

    @Test
    fun axes_missing_stylus_only_navigation_temporary_eraser_and_hover_behave_honestly() {
        val engine = InkEngine()

        // 1. axes_missing: passive capacitive pen / finger without pressure or tilt support
        val passiveDown = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 1L,
                action = InputActionKind.DOWN,
                toolType = InputToolType.FINGER,
                supportsPressure = false,
                supportsTilt = false,
                realSamples = listOf(RealSample(10f, 10f, 0.99f, 0.88f, 10L))
            ).getOrNull()!!,
            baseCtx.copy(policy = StylusInputPolicy(stylusOnlyDrawing = false, palmRejectionEnabled = false))
        ).getOrNull()!!
        assertFalse(passiveDown.capabilityReport.supportsPressure)
        assertFalse(passiveDown.capabilityReport.supportsTilt)
        assertTrue(passiveDown.capabilityReport.isPassiveTouch)
        // Missing axes must fall back to neutral constants (pressure=0.5f, tilt=0.0f), never fake readings
        val draftSample = passiveDown.draftStroke!!.payload.samples.single()
        assertEquals(0.5f, draftSample.pressure, 1e-4f)
        assertEquals(0.0f, draftSample.tilt, 1e-4f)

        // Cancel passive stroke before next check
        engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 2L,
                action = InputActionKind.CANCEL,
                toolType = InputToolType.FINGER,
                supportsPressure = false,
                supportsTilt = false,
                realSamples = listOf(RealSample(10f, 10f, 0.5f, 0f, 12L))
            ).getOrNull()!!,
            baseCtx
        )

        // 2. stylusOnlyDrawing = true -> finger touch becomes NAVIGATION delta instead of drawing
        val stylusOnlyCtx = baseCtx.copy(
            policy = StylusInputPolicy(stylusOnlyDrawing = true, palmRejectionEnabled = true)
        )
        val navDown = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 3L,
                action = InputActionKind.DOWN,
                toolType = InputToolType.FINGER,
                supportsPressure = false,
                supportsTilt = false,
                realSamples = listOf(RealSample(100f, 100f, 0.5f, 0f, 20L))
            ).getOrNull()!!,
            stylusOnlyCtx
        ).getOrNull()!!
        assertEquals(EffectiveInkToolMode.NAVIGATION, navDown.effectiveToolMode)
        assertNull(navDown.draftStroke)

        val navMove = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = 4L,
                action = InputActionKind.MOVE,
                toolType = InputToolType.FINGER,
                supportsPressure = false,
                supportsTilt = false,
                realSamples = listOf(RealSample(130f, 145f, 0.5f, 0f, 28L))
            ).getOrNull()!!,
            stylusOnlyCtx
        ).getOrNull()!!
        assertEquals(EffectiveInkToolMode.NAVIGATION, navMove.effectiveToolMode)
        assertNotNull(navMove.navigationDeltaDoc)
        assertEquals(30.0, navMove.navigationDeltaDoc!!.x, 1e-3)
        assertEquals(45.0, navMove.navigationDeltaDoc!!.y, 1e-3)

        // 3. Temporary eraser button + HOVER
        val hoverOut = engine.ingest(
            InputBatch.fromSamples(
                session = session,
                pointerId = 1,
                sequenceNumber = 5L,
                action = InputActionKind.HOVER,
                toolType = InputToolType.STYLUS,
                secondaryEraserButtonPressed = true,
                realSamples = listOf(RealSample(80f, 90f, 0.0f, 0.2f, 40L))
            ).getOrNull()!!,
            stylusOnlyCtx
        ).getOrNull()!!
        assertEquals(EffectiveInkToolMode.TEMPORARY_ERASER, hoverOut.effectiveToolMode)
        assertNotNull(hoverOut.hoverOverlay)
    }

    @Test
    fun long_stroke_exceeds_resident_window_by_Over_10x_spools_chunks_and_commits_single_undo_and_aborts_on_diskfull() {
        val spool = InMemoryStrokeSpoolSink()
        val engine = InkEngine(spoolSink = spool)

        // Configure a 4 KiB resident window (max 128 samples per chunk = 3072 bytes)
        // Stream 1,600 samples = 38,400 bytes (~10.5x the resident window!)
        val windowCapBytes = 3600L
        val longCtx = baseCtx.copy(
            maxResidentWindowBytes = windowCapBytes,
            maxSamplesPerResidentChunk = 128
        )

        var seq = 1L
        val totalSamples = 1600
        val batchSize = 32
        var maxObservedResidentBytes = 0L

        for (offset in 0 until totalSamples step batchSize) {
            val action = when {
                offset == 0 -> InputActionKind.DOWN
                offset + batchSize >= totalSamples -> InputActionKind.UP
                else -> InputActionKind.MOVE
            }
            val samples = (0 until batchSize).map { i ->
                val idx = offset + i
                RealSample(
                    x = (idx * 2).toFloat(),
                    y = (idx % 50).toFloat(),
                    pressure = 0.5f,
                    tilt = 0.1f,
                    timestampMs = 1000L + idx * 4L
                )
            }
            val batch = InputBatch.fromSamples(
                session = session,
                pointerId = 0,
                sequenceNumber = seq++,
                action = action,
                realSamples = samples
            ).getOrNull()!!
            val out = engine.ingest(batch, longCtx).getOrNull()!!
            if (out.residentWindowBytes > maxObservedResidentBytes) {
                maxObservedResidentBytes = out.residentWindowBytes
            }
            assertTrue(
                out.residentWindowBytes <= windowCapBytes,
                "Resident window (${out.residentWindowBytes} B) must never exceed cap ($windowCapBytes B)"
            )

            if (action == InputActionKind.UP) {
                assertTrue(out.spooledChunkRefs.size >= 10, "Expected >= 10 spooled chunks, got ${out.spooledChunkRefs.size}")
                val plan = out.commitPlan
                assertNotNull(plan, "UP on long_stroke must produce a single atomic EditPlan")

                // Apply the plan to DocumentState and verify single-step Undo/Redo and 100% real sample preservation
                val state = DocumentState.createInitial(docId)
                val limits = Limits()
                val validated = validatePlan(state.snapshot(), plan!!, limits).getOrNull()!!
                val applied = applyPlan(state, validated, limits).getOrNull()!!
                assertEquals(Revision(1L), applied.revision, "Long stroke must commit in a single revision")

                val committedStrokes = state.snapshot().orderedSceneObjects()
                    .map { it.payload as StrokePayload }
                val recoveredSamplesCount = committedStrokes.sumOf { it.samples.size }
                assertEquals(totalSamples, recoveredSamplesCount, "100% of real samples ($totalSamples) must be preserved")

                // Verify single Undo (applied.inversePlan) removes the entire long stroke in one action
                applyPlan(state, validatePlan(state.snapshot(), applied.inversePlan, limits).getOrNull()!!, limits)
                assertEquals(0, state.snapshot().totalObjectCount, "One Undo must remove the entire long stroke")
            }
        }

        // Now test DiskFull during spooling: must abort and cancel the draft explicitly without silent truncation
        spool.failOnNextSpool = true
        var sawDiskFullFailure = false
        for (offset in 0 until 400 step 64) {
            val action = if (offset == 0) InputActionKind.DOWN else InputActionKind.MOVE
            val samples = (0 until 64).map { i ->
                val idx = offset + i
                RealSample(idx.toFloat(), idx.toFloat(), 0.5f, 0f, 5000L + idx)
            }
            val res = engine.ingest(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = seq++,
                    action = action,
                    realSamples = samples
                ).getOrNull()!!,
                longCtx
            )
            if (res.isFailure) {
                assertTrue(res.errorOrNull() is InputError.SpoolFailed)
                sawDiskFullFailure = true
                break
            }
        }
        assertTrue(sawDiskFullFailure, "DiskFull during spooling must fail with InputError.SpoolFailed")
    }

    @Test
    fun all_five_brushes_and_corner_preserving_smoothing_produce_canonical_vector_geometry() {
        val geometryPort = InkGeometryPort()

        // L-shaped stroke with an intentional 90-degree sharp corner at (50, 0)
        val cornerSamples = listOf(
            RealSample(0f, 0f, 0.4f, 0.1f, 0L),
            RealSample(25f, 0f, 0.6f, 0.2f, 10L),
            RealSample(50f, 0f, 0.8f, 0.3f, 20L), // Sharp 90-degree corner!
            RealSample(50f, 25f, 0.6f, 0.2f, 30L),
            RealSample(50f, 50f, 0.4f, 0.1f, 40L)
        )

        for (kind in BrushKind.entries) {
            for (pattern in listOf(StrokeLinePattern.SOLID, StrokeLinePattern.DASHED, StrokeLinePattern.DOTTED)) {
                val spec = BrushSpec(
                    kind = kind,
                    colorArgb = 0xFF112233.toInt(),
                    baseWidthPt = 3.0,
                    smoothingVersion = 1,
                    smoothingStrength = 0.6f,
                    endCap = when (kind) {
                        BrushKind.HIGHLIGHTER -> StrokeEndCap.FLAT
                        BrushKind.FOUNTAIN, BrushKind.BRUSH -> StrokeEndCap.TAPERED
                        else -> StrokeEndCap.ROUND
                    },
                    linePattern = pattern
                )
                val payload = BrushPipeline.buildStrokePayload(
                    origin = DocPoint(100.0, 200.0),
                    realSamples = cornerSamples,
                    brush = spec
                )
                val pathOutcome = geometryPort.paths(
                    GeometryRequest(
                        strokeId = inkspace.core.id.ObjectId("test-${kind.canonicalId}-${pattern.name}"),
                        origin = payload.origin,
                        samples = payload.samples.map { DocPoint(it.relX.toDouble(), it.relY.toDouble()) },
                        baseWidthPt = payload.baseWidthPt,
                        brushId = payload.brushId,
                        smoothingVersion = payload.smoothingVersion,
                        scaleBucket = 0
                    )
                )
                assertTrue(pathOutcome.isSuccess)
                val artifact = pathOutcome.getOrNull()!!
                assertTrue(artifact.relativePoints.size >= 5)

                // Verify the intentional 90-degree corner at world (50, 0) is preserved accurately!
                val hasExactCornerVertex = artifact.relativePoints.any { pt ->
                    val worldX = artifact.origin.x + pt.x
                    val worldY = artifact.origin.y + pt.y
                    abs(worldX - 50.0) <= 0.05 && abs(worldY - 0.0) <= 0.05
                }
                assertTrue(
                    hasExactCornerVertex,
                    "Brush ${kind.canonicalId} ($pattern) must preserve intentional sharp corner at (50, 0)"
                )
            }
        }
    }
}
