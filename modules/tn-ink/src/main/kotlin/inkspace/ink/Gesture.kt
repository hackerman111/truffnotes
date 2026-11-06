package inkspace.ink

import inkspace.core.id.ObjectId
import inkspace.core.ink.ActiveToolKind
import inkspace.core.ink.BrushKind
import inkspace.core.ink.EffectiveInkToolMode
import inkspace.core.ink.GestureDecision
import inkspace.core.ink.GesturePriorityLevel
import inkspace.core.ink.GestureProposal
import inkspace.core.ink.GestureSettings
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputContext
import inkspace.core.ink.InputToolType
import inkspace.core.ink.MultiTapGestureEvent
import inkspace.core.objects.RecognitionConfig
import inkspace.core.objects.ShapeCandidate
import inkspace.core.objects.StrokeData
import inkspace.core.runtime.InputActionKind
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classifies incoming [InputBatch] tool mode according to [InputContext.policy],
 * active stylus pointers, temporary eraser button state, and hover/cancel flags.
 */
object InkToolArbiter {

    fun classifyToolMode(
        batch: InputBatch,
        ctx: InputContext,
        hasOtherActiveStylusPointer: Boolean,
        lastStylusTimestampMs: Long
    ): EffectiveInkToolMode {
        if (batch.isEffectiveCancel) {
            return EffectiveInkToolMode.CANCELED
        }

        val isTemporaryEraser = batch.toolType == InputToolType.ERASER ||
            (ctx.policy.temporaryEraserButtonEnabled && batch.secondaryEraserButtonPressed)

        if (batch.action == InputActionKind.HOVER) {
            return if (isTemporaryEraser) {
                EffectiveInkToolMode.TEMPORARY_ERASER
            } else {
                EffectiveInkToolMode.HOVER
            }
        }

        // Palm rejection: when an active stylus pointer is drawing (or just drew within palmRejectionWindowMs),
        // concurrent FINGER touches are rejected as palm contact without disturbing the stylus pointer.
        if (batch.toolType == InputToolType.FINGER && ctx.policy.palmRejectionEnabled) {
            val firstTs = if (batch.realSampleCount > 0) batch.realTimestampMs(0) else 0L
            val withinPalmWindow = lastStylusTimestampMs > Long.MIN_VALUE &&
                (firstTs - lastStylusTimestampMs) in -50L..ctx.policy.palmRejectionWindowMs
            if (hasOtherActiveStylusPointer || withinPalmWindow) {
                return EffectiveInkToolMode.PALM_REJECTED
            }
        }

        // Stylus-only drawing policy: FINGER touch performs camera pan/scroll navigation instead of drawing
        if (batch.toolType == InputToolType.FINGER && ctx.policy.stylusOnlyDrawing) {
            return EffectiveInkToolMode.NAVIGATION
        }

        return if (isTemporaryEraser) {
            EffectiveInkToolMode.TEMPORARY_ERASER
        } else {
            EffectiveInkToolMode.DRAW
        }
    }
}

/**
 * Unified gesture arbiter enforcing the 6-level priority hierarchy (`F08.4`).
 *
 * Precedence:
 * 1. [GesturePriorityLevel.SYSTEM_INTERCEPT] (Android system gestures, edge back swipes)
 * 2. [GesturePriorityLevel.ACTIVE_OPERATION] (In-progress selection transforms, drag-drop)
 * 3. [GesturePriorityLevel.EXPLICIT_TOOL] (When non-pen tool is selected, e.g. Eraser, Lasso, Ruler)
 * 4. [GesturePriorityLevel.FINGER_NAVIGATION] (Finger pan/zoom under stylusOnlyDrawing)
 * 5. [GesturePriorityLevel.STYLUS_GEOMETRIC_GESTURE] (Hold-to-straighten, hold-to-shape, circle-to-lasso, scribble-to-erase)
 * 6. [GesturePriorityLevel.NORMAL_INK] (Standard handwriting stroke)
 */
object GestureArbiter {

    @Volatile
    var shapeRecognizer: ((StrokeData, RecognitionConfig) -> ShapeCandidate)? = null

    fun arbitrate(
        stroke: StrokeData,
        ctx: InputContext,
        strokeId: ObjectId = ObjectId("stroke-gesture"),
        candidateErasedStrokes: List<Pair<ObjectId, DocRect>> = emptyList(),
        recentClosedLoop: Pair<ObjectId, DocRect>? = null,
        recognizer: ((StrokeData, RecognitionConfig) -> ShapeCandidate)? = shapeRecognizer
    ): GestureDecision {
        val settings = ctx.gestureSettings

        // 1. SYSTEM_INTERCEPT
        if (ctx.isSystemGestureIntercepted) {
            return GestureDecision.ConsumeAsSystem(
                GestureProposal.SystemIntercept("Android system gesture intercepted")
            )
        }

        // 2. ACTIVE_OPERATION
        if (ctx.hasActiveOperation) {
            return GestureDecision.Ignored
        }

        // 3. EXPLICIT_TOOL
        if (ctx.activeTool != ActiveToolKind.PEN) {
            return GestureDecision.FallbackToInk
        }

        // 5. STYLUS_GEOMETRIC_GESTURE (gated by settings.geometricGesturesEnabled)
        if (!settings.geometricGesturesEnabled) {
            return GestureDecision.FallbackToInk
        }

        // 5a. Scribble-to-erase check (only Pen brushes: BALL, FOUNTAIN, BRUSH)
        if (settings.scribbleEraseEnabled && isPenBrushEligibleForScribble(stroke.brushKind)) {
            val scribbleProposal = detectScribbleErase(stroke, settings, candidateErasedStrokes)
            if (scribbleProposal != null) {
                return GestureDecision.ExecuteScribbleErase(scribbleProposal)
            }
        }

        // 5b. Circle-to-lasso check (circle-lift-rehold)
        if (settings.circleToLassoEnabled && recentClosedLoop != null) {
            val lassoProposal = detectCircleToLassoRehold(stroke, settings, recentClosedLoop)
            if (lassoProposal != null) {
                return GestureDecision.ExecuteLassoSelection(lassoProposal)
            }
        }

        // 5c. Hold-to-shape / Hold-to-straighten check
        if (settings.holdToShapeEnabled && stroke.holdDurationMs >= settings.holdThresholdMs) {
            val activeRecognizer = recognizer
            if (activeRecognizer != null) {
                val recognitionConfig = RecognitionConfig(
                    drawAndHoldEnabled = true,
                    holdThresholdMs = settings.holdThresholdMs
                )
                val candidate = activeRecognizer(stroke, recognitionConfig)
                if (candidate is ShapeCandidate.Recognized) {
                    return GestureDecision.ExecuteShape(
                        GestureProposal.ShapeHold(
                            strokeId = strokeId,
                            shapeCandidate = candidate,
                            holdDurationMs = stroke.holdDurationMs
                        )
                    )
                }
            }
        }

        // 6. NORMAL_INK
        return GestureDecision.FallbackToInk
    }

    fun arbitrateMultiTap(
        event: MultiTapGestureEvent,
        settings: GestureSettings,
        hasActiveOperation: Boolean,
        isSystemIntercepted: Boolean
    ): GestureDecision {
        if (isSystemIntercepted) {
            return GestureDecision.ConsumeAsSystem(
                GestureProposal.SystemIntercept("System gesture intercepted multi-tap")
            )
        }
        if (hasActiveOperation) {
            return GestureDecision.Ignored
        }

        // 2-finger double-tap -> Undo
        if (event.fingerCount == 2 && event.tapCount == 2 && settings.twoFingerUndoEnabled) {
            return GestureDecision.ExecuteUndoRedo(
                GestureProposal.MultiFingerUndoRedo(isRedo = false, fingerCount = 2)
            )
        }

        // 3-finger double-tap -> Redo
        if (event.fingerCount == 3 && event.tapCount == 2 && settings.threeFingerRedoEnabled) {
            return GestureDecision.ExecuteUndoRedo(
                GestureProposal.MultiFingerUndoRedo(isRedo = true, fingerCount = 3)
            )
        }

        // Single-finger double tap or other taps are ignored here (delegated to viewport double-tap zoom)
        return GestureDecision.Ignored
    }

    private fun isPenBrushEligibleForScribble(kind: BrushKind): Boolean {
        return kind == BrushKind.BALL || kind == BrushKind.FOUNTAIN || kind == BrushKind.BRUSH
    }

    fun detectScribbleErase(
        stroke: StrokeData,
        settings: GestureSettings,
        candidateErasedStrokes: List<Pair<ObjectId, DocRect>>
    ): GestureProposal.ScribbleErase? {
        val points = stroke.points
        if (points.size < settings.scribbleMinReversals * 3) return null

        var reversals = 0
        var prevDirection = 0 // +1 or -1
        val step = max(1, points.size / 25)

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val envelope = DocRect(minX, minY, maxX, maxY)

        var i = 0
        while (i < points.size - step) {
            val p1 = points[i]
            val p2 = points[i + step]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val dir = if (abs(dx) > abs(dy)) {
                if (dx > 2.0) 1 else if (dx < -2.0) -1 else 0
            } else {
                if (dy > 2.0) 1 else if (dy < -2.0) -1 else 0
            }

            if (dir != 0) {
                if (prevDirection != 0 && dir != prevDirection) {
                    reversals++
                }
                prevDirection = dir
            }
            i += step
        }

        if (reversals >= settings.scribbleMinReversals) {
            // Find strokes intersecting the scribble envelope
            val erasedIds = LinkedHashSet<ObjectId>()
            for ((id, rect) in candidateErasedStrokes) {
                if (envelope.intersects(rect)) {
                    erasedIds.add(id)
                }
            }
            if (erasedIds.isNotEmpty()) {
                return GestureProposal.ScribbleErase(
                    erasedStrokeIds = erasedIds,
                    envelope = envelope
                )
            }
        }
        return null
    }

    private fun detectCircleToLassoRehold(
        stroke: StrokeData,
        settings: GestureSettings,
        recentClosedLoop: Pair<ObjectId, DocRect>
    ): GestureProposal.CircleToLasso? {
        val points = stroke.points
        if (points.isEmpty()) return null
        val touchPt = points.first()

        val (loopId, loopRect) = recentClosedLoop

        // Check if touch is on or near the perimeter of the closed loop (within circleReholdLineTolerancePt)
        val distToPerimeter = distanceToRectPerimeter(touchPt, loopRect)
        if (distToPerimeter <= settings.circleReholdLineTolerancePt) {
            if (stroke.holdDurationMs >= settings.circleReholdThresholdMs) {
                return GestureProposal.CircleToLasso(
                    strokeId = loopId,
                    enclosedBounds = loopRect
                )
            }
        }
        return null
    }

    private fun distanceToRectPerimeter(p: DocPoint, r: DocRect): Double {
        val dx = max(0.0, max(r.left - p.x, p.x - r.right))
        val dy = max(0.0, max(r.top - p.y, p.y - r.bottom))
        if (dx > 0 || dy > 0) {
            return sqrt(dx * dx + dy * dy)
        }
        // Inside rectangle: distance to nearest edge
        return min(min(p.x - r.left, r.right - p.x), min(p.y - r.top, r.bottom - p.y))
    }
}
