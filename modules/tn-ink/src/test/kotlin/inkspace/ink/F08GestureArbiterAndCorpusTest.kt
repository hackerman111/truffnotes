package inkspace.ink

import inkspace.core.id.DocumentId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.ink.ActiveToolKind
import inkspace.core.ink.BrushKind
import inkspace.core.ink.GestureDecision
import inkspace.core.ink.GestureProposal
import inkspace.core.ink.GestureSettings
import inkspace.core.ink.InputContext
import inkspace.core.ink.MultiTapGestureEvent
import inkspace.core.model.DocVersion
import inkspace.core.objects.ShapeCandidate
import inkspace.core.objects.StrokeData
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.objects.ShapeRecognizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class F08GestureArbiterAndCorpusTest {

    private val baseCtx = InputContext(
        docVersion = DocVersion(DocumentId("doc-gestures"), Revision(0L)),
        activeSpaceId = SpaceId("s1"),
        activePageId = PageId("p1"),
        activeLayerId = LayerId("l1"),
        activeTool = ActiveToolKind.PEN,
        gestureSettings = GestureSettings()
    )

    @BeforeEach
    fun setUp() {
        GestureArbiter.shapeRecognizer = ShapeRecognizer::recognize
    }

    private fun makeStrokeData(
        points: List<DocPoint>,
        holdMs: Long = 0L,
        brush: BrushKind = BrushKind.BALL,
        width: Double = 2.0,
        color: Int = 0xFF000000.toInt()
    ): StrokeData {
        val origin = points.firstOrNull() ?: DocPoint(0.0, 0.0)
        val samples = points.map { pt ->
            StrokeSamplePoint(
                relX = (pt.x - origin.x).toFloat(),
                relY = (pt.y - origin.y).toFloat(),
                pressure = 0.5f,
                tilt = 0.0f,
                timeDeltaMs = 16
            )
        }
        return StrokeData(
            origin = origin,
            samples = samples,
            brushKind = brush,
            baseWidthPt = width,
            colorArgb = color,
            holdDurationMs = holdMs
        )
    }

    @Test
    fun testPriorityLevel1SystemIntercept() {
        val linePts = (0..20).map { DocPoint(100.0 + it * 10.0, 100.0) }
        val stroke = makeStrokeData(linePts, holdMs = 600L)

        val ctxIntercepted = baseCtx.copy(isSystemGestureIntercepted = true)
        val decision = GestureArbiter.arbitrate(stroke, ctxIntercepted)
        assertTrue(decision is GestureDecision.ConsumeAsSystem)

        val multiTapDecision = GestureArbiter.arbitrateMultiTap(
            event = MultiTapGestureEvent(
                fingerCount = 2,
                tapCount = 2,
                positionDoc = DocPoint(100.0, 100.0),
                timestampMs = 1000L
            ),
            settings = baseCtx.gestureSettings,
            hasActiveOperation = false,
            isSystemIntercepted = true
        )
        assertTrue(multiTapDecision is GestureDecision.ConsumeAsSystem)
    }

    @Test
    fun testPriorityLevel2ActiveOperation() {
        val linePts = (0..20).map { DocPoint(100.0 + it * 10.0, 100.0) }
        val stroke = makeStrokeData(linePts, holdMs = 600L)

        val ctxActiveOp = baseCtx.copy(hasActiveOperation = true)
        val decision = GestureArbiter.arbitrate(stroke, ctxActiveOp)
        assertEquals(GestureDecision.Ignored, decision)

        val tapDecision = GestureArbiter.arbitrateMultiTap(
            event = MultiTapGestureEvent(
                fingerCount = 2,
                tapCount = 2,
                positionDoc = DocPoint(100.0, 100.0),
                timestampMs = 1000L
            ),
            settings = baseCtx.gestureSettings,
            hasActiveOperation = true,
            isSystemIntercepted = false
        )
        assertEquals(GestureDecision.Ignored, tapDecision)
    }

    @Test
    fun testPriorityLevel3ExplicitTool() {
        val linePts = (0..20).map { DocPoint(100.0 + it * 10.0, 100.0) }
        val stroke = makeStrokeData(linePts, holdMs = 600L)

        // Non-pen tools fallback to ink / do not trigger shape or scribble gestures
        val ctxEraser = baseCtx.copy(activeTool = ActiveToolKind.ERASER)
        assertEquals(GestureDecision.FallbackToInk, GestureArbiter.arbitrate(stroke, ctxEraser))

        val ctxLasso = baseCtx.copy(activeTool = ActiveToolKind.LASSO)
        assertEquals(GestureDecision.FallbackToInk, GestureArbiter.arbitrate(stroke, ctxLasso))
    }

    @Test
    fun testPriorityLevel5aGeometricGesturesDisabledFallbackToInk() {
        // acceptance30: Geometric gestures can be disabled
        val linePts = (0..20).map { DocPoint(100.0 + it * 10.0, 100.0) }
        val stroke = makeStrokeData(linePts, holdMs = 600L)

        val disabledSettings = baseCtx.gestureSettings.copy(geometricGesturesEnabled = false)
        val ctxDisabled = baseCtx.copy(gestureSettings = disabledSettings)

        val decision = GestureArbiter.arbitrate(stroke, ctxDisabled)
        assertEquals(GestureDecision.FallbackToInk, decision)
    }

    @Test
    fun testPriorityLevel5bHoldToStraightenLine() {
        // acceptance26: Hold after straight line straightens it
        val linePts = (0..20).map { DocPoint(100.0 + it * 10.0, 100.0) }
        val stroke = makeStrokeData(linePts, holdMs = 500L)

        val decision = GestureArbiter.arbitrate(stroke, baseCtx)
        assertTrue(decision is GestureDecision.ExecuteShape)
        val shapeProp = (decision as GestureDecision.ExecuteShape).proposal
        val candidate = shapeProp.shapeCandidate
        assertTrue(candidate is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Line, (candidate as ShapeCandidate.Recognized).shapeType)
    }

    @Test
    fun testPriorityLevel5cHoldToCircle() {
        // acceptance27: Hold after circle converts to shape
        val circlePts = (0..32).map { i ->
            val angle = (i.toDouble() / 32) * 2 * PI
            DocPoint(200.0 + 50.0 * cos(angle), 200.0 + 50.0 * sin(angle))
        }
        val stroke = makeStrokeData(circlePts, holdMs = 500L)

        val decision = GestureArbiter.arbitrate(stroke, baseCtx)
        assertTrue(decision is GestureDecision.ExecuteShape)
        val shapeProp = (decision as GestureDecision.ExecuteShape).proposal
        val candidate = shapeProp.shapeCandidate
        assertTrue(candidate is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Circle, (candidate as ShapeCandidate.Recognized).shapeType)
    }

    @Test
    fun testHoldSubThresholdFallsBackToInk() {
        val linePts = (0..20).map { DocPoint(100.0 + it * 10.0, 100.0) }
        // Default threshold is 400ms; hold 250ms must not trigger shape
        val stroke = makeStrokeData(linePts, holdMs = 250L)

        val decision = GestureArbiter.arbitrate(stroke, baseCtx)
        assertEquals(GestureDecision.FallbackToInk, decision)
    }

    @Test
    fun testScribbleToEraseOnPenBrushes() {
        // Rapid zig-zag stroke over candidate target stroke
        val targetId = ObjectId("target-stroke-1")
        val candidateStrokes = listOf(targetId to DocRect(100.0, 100.0, 140.0, 140.0))

        val scribblePts = ArrayList<DocPoint>()
        for (i in 0..30) {
            val x = 90.0 + (i % 2) * 60.0
            val y = 90.0 + i * 2.0
            scribblePts.add(DocPoint(x, y))
        }

        // 1. BALL pen brush -> succeeds
        val strokeBall = makeStrokeData(scribblePts, brush = BrushKind.BALL)
        val decisionBall = GestureArbiter.arbitrate(strokeBall, baseCtx, candidateErasedStrokes = candidateStrokes)
        assertTrue(decisionBall is GestureDecision.ExecuteScribbleErase)
        val erasedIds = (decisionBall as GestureDecision.ExecuteScribbleErase).proposal.erasedStrokeIds
        assertTrue(erasedIds.contains(targetId))

        // 2. FOUNTAIN pen brush -> succeeds
        val strokeFountain = makeStrokeData(scribblePts, brush = BrushKind.FOUNTAIN)
        val decisionFountain = GestureArbiter.arbitrate(strokeFountain, baseCtx, candidateErasedStrokes = candidateStrokes)
        assertTrue(decisionFountain is GestureDecision.ExecuteScribbleErase)

        // 3. BRUSH pen brush -> succeeds
        val strokeBrush = makeStrokeData(scribblePts, brush = BrushKind.BRUSH)
        val decisionBrush = GestureArbiter.arbitrate(strokeBrush, baseCtx, candidateErasedStrokes = candidateStrokes)
        assertTrue(decisionBrush is GestureDecision.ExecuteScribbleErase)

        // 4. HIGHLIGHTER -> NOT eligible for scribble erase
        val strokeHighlighter = makeStrokeData(scribblePts, brush = BrushKind.HIGHLIGHTER)
        val decisionHighlighter = GestureArbiter.arbitrate(strokeHighlighter, baseCtx, candidateErasedStrokes = candidateStrokes)
        assertEquals(GestureDecision.FallbackToInk, decisionHighlighter)

        // 5. PENCIL -> NOT eligible for scribble erase
        val strokePencil = makeStrokeData(scribblePts, brush = BrushKind.PENCIL)
        val decisionPencil = GestureArbiter.arbitrate(strokePencil, baseCtx, candidateErasedStrokes = candidateStrokes)
        assertEquals(GestureDecision.FallbackToInk, decisionPencil)
    }

    @Test
    fun testCircleToLassoSelection() {
        // acceptance28: Circle-lift-rehold selects enclosed objects
        val loopId = ObjectId("closed-loop-1")
        val loopRect = DocRect(100.0, 100.0, 300.0, 300.0)

        // Touch on the perimeter of the loop at (100.0, 200.0) held for 400ms (threshold is 300ms)
        val touchPts = listOf(DocPoint(100.0, 200.0), DocPoint(101.0, 200.0))
        val reholdStroke = makeStrokeData(touchPts, holdMs = 400L)

        val decision = GestureArbiter.arbitrate(
            reholdStroke,
            baseCtx,
            recentClosedLoop = loopId to loopRect
        )
        assertTrue(decision is GestureDecision.ExecuteLassoSelection)
        val lassoProp = (decision as GestureDecision.ExecuteLassoSelection).proposal
        assertEquals(loopId, lassoProp.strokeId)
        assertEquals(loopRect, lassoProp.enclosedBounds)
    }

    @Test
    fun testMultiFingerTapUndoRedo() {
        val settings = baseCtx.gestureSettings
        val pt = DocPoint(200.0, 200.0)

        // 2-finger double-tap -> Undo
        val undoEvent = MultiTapGestureEvent(fingerCount = 2, tapCount = 2, positionDoc = pt, timestampMs = 1000L)
        val undoDecision = GestureArbiter.arbitrateMultiTap(undoEvent, settings, false, false)
        assertTrue(undoDecision is GestureDecision.ExecuteUndoRedo)
        assertFalse((undoDecision as GestureDecision.ExecuteUndoRedo).proposal.isRedo)
        assertEquals(2, undoDecision.proposal.fingerCount)

        // 3-finger double-tap -> Redo
        val redoEvent = MultiTapGestureEvent(fingerCount = 3, tapCount = 2, positionDoc = pt, timestampMs = 1000L)
        val redoDecision = GestureArbiter.arbitrateMultiTap(redoEvent, settings, false, false)
        assertTrue(redoDecision is GestureDecision.ExecuteUndoRedo)
        assertTrue((redoDecision as GestureDecision.ExecuteUndoRedo).proposal.isRedo)
        assertEquals(3, redoDecision.proposal.fingerCount)

        // 1-finger double-tap -> Ignored (preserved for canvas zoom)
        val singleFingerEvent = MultiTapGestureEvent(fingerCount = 1, tapCount = 2, positionDoc = pt, timestampMs = 1000L)
        val singleTapDecision = GestureArbiter.arbitrateMultiTap(singleFingerEvent, settings, false, false)
        assertEquals(GestureDecision.Ignored, singleTapDecision)

        // 2-finger single tap -> Ignored
        val twoFingerSingle = MultiTapGestureEvent(fingerCount = 2, tapCount = 1, positionDoc = pt, timestampMs = 1000L)
        assertEquals(GestureDecision.Ignored, GestureArbiter.arbitrateMultiTap(twoFingerSingle, settings, false, false))
    }

    @Test
    fun testSettingsResetToDefaults() {
        val custom = GestureSettings(
            geometricGesturesEnabled = false,
            holdToShapeEnabled = false,
            holdThresholdMs = 999L,
            scribbleEraseEnabled = false,
            twoFingerUndoEnabled = false
        )
        val reset = custom.resetToDefaults()
        assertTrue(reset.geometricGesturesEnabled)
        assertTrue(reset.holdToShapeEnabled)
        assertEquals(400L, reset.holdThresholdMs)
        assertTrue(reset.scribbleEraseEnabled)
        assertTrue(reset.twoFingerUndoEnabled)
    }
}
