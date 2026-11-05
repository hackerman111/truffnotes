package inkspace.ink

import inkspace.core.ink.EffectiveInkToolMode
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputContext
import inkspace.core.ink.InputToolType
import inkspace.core.runtime.InputActionKind

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
