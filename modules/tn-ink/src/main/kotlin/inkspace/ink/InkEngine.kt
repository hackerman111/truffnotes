package inkspace.ink

import inkspace.core.error.InputError
import inkspace.core.id.GestureId
import inkspace.core.id.ObjectId
import inkspace.core.ink.EffectiveInkToolMode
import inkspace.core.ink.InkOutput
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputContext
import inkspace.core.ink.InputToolType
import inkspace.core.ink.StrokeSpoolSink
import inkspace.core.outcome.Outcome
import inkspace.core.render.EphemeralOverlayItem
import inkspace.core.runtime.InputActionKind
import inkspace.core.unit.DocPoint
import inkspace.core.unit.ViewPoint
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Authoritative handwriting input engine (`:tn-ink`).
 *
 * API: `InkEngine.ingest(batch: InputBatch, ctx: InputContext): Outcome<InkOutput, InputError>`
 *
 * - Processes `DOWN`, `MOVE`, `UP`, `CANCEL` (`FLAG_CANCELED`), and `HOVER`.
 * - Tracks per-pointer active stroke sessions and assigns a fresh `strokeEpoch`, `ObjectId`, and `GestureId`
 *   whenever a `pointerId` is reused after `UP` or `CANCEL`.
 * - Enforces palm rejection (rejecting concurrent `FINGER` touches without disturbing an active or completed
 *   `STYLUS` stroke on another pointer) and `stylusOnlyDrawing` navigation fallback.
 * - Keeps active draft sample memory within `ctx.maxResidentWindowBytes` (`<= 8 MiB`), spooling older chunks
 *   to [spoolSink] and publishing all chunks as a single atomic undoable [inkspace.core.mutation.EditPlan] on `UP`.
 * - Strictly separates [inkspace.core.ink.RealSample] from [inkspace.core.ink.PredictedSample]: predicted points
 *   only populate `InkOutput.predictedOverlay` and never enter `EditPlan`, `StrokePayload`, `Undo`, or storage.
 */
class InkEngine(
    private val spoolSink: StrokeSpoolSink = InMemoryStrokeSpoolSink()
) {
    private val strokeEpochGen = AtomicLong(1L)
    private val activePointers = LinkedHashMap<Int, ActivePointerStrokeState>()
    private val navigationAnchors = LinkedHashMap<Int, DocPoint>()
    private var lastStylusActivityTimestampMs: Long = Long.MIN_VALUE

    @Synchronized
    fun activePointerCount(): Int = activePointers.size

    @Synchronized
    fun ingest(
        batch: InputBatch,
        ctx: InputContext
    ): Outcome<InkOutput, InputError> {
        if (ctx.isLayerLocked && batch.action != InputActionKind.HOVER && !batch.isEffectiveCancel) {
            return Outcome.Failure(
                InputError.LockedTarget(
                    layerId = ctx.activeLayerId.value,
                    reason = "Active layer ${ctx.activeLayerId.value} is locked"
                )
            )
        }

        if (batch.toolType == InputToolType.STYLUS || batch.toolType == InputToolType.ERASER) {
            val lastTs = batch.realTimestampMs(batch.realSampleCount - 1)
            lastStylusActivityTimestampMs = max(lastStylusActivityTimestampMs, lastTs)
        }

        // 1. Handle global ACTION_CANCEL vs per-pointer FLAG_CANCELED
        if (batch.isEffectiveCancel) {
            navigationAnchors.remove(batch.pointerId)
            return if (batch.action == InputActionKind.CANCEL && !batch.isFlagCanceled) {
                // ACTION_CANCEL cancels all active touch pointers in the stream
                var canceledEpoch = 0L
                val toCancel = activePointers.values.toList()
                activePointers.clear()
                navigationAnchors.clear()
                for (st in toCancel) {
                    if (st.pointerId == batch.pointerId) canceledEpoch = st.strokeEpoch
                    st.residentSamples.clear()
                    spoolSink.discardSpooledChunks(st.strokeId)
                }
                Outcome.Success(
                    InkOutput(
                        pointerId = batch.pointerId,
                        strokeEpoch = canceledEpoch,
                        effectiveToolMode = EffectiveInkToolMode.CANCELED,
                        capabilityReport = batch.capabilityReport,
                        residentWindowBytes = 0L
                    )
                )
            } else {
                // Per-pointer FLAG_CANCELED: cancel ONLY this pointerId, preserving other pointers!
                val removed = activePointers.remove(batch.pointerId)
                if (removed != null) {
                    removed.residentSamples.clear()
                    spoolSink.discardSpooledChunks(removed.strokeId)
                }
                Outcome.Success(
                    InkOutput(
                        pointerId = batch.pointerId,
                        strokeEpoch = removed?.strokeEpoch ?: 0L,
                        effectiveToolMode = EffectiveInkToolMode.CANCELED,
                        capabilityReport = batch.capabilityReport,
                        residentWindowBytes = 0L
                    )
                )
            }
        }

        val hasOtherActiveStylus = activePointers.values.any {
            it.pointerId != batch.pointerId &&
                (it.mode == EffectiveInkToolMode.DRAW || it.mode == EffectiveInkToolMode.TEMPORARY_ERASER)
        }

        val existingState = activePointers[batch.pointerId]
        val classifiedMode = existingState?.mode ?: InkToolArbiter.classifyToolMode(
            batch = batch,
            ctx = ctx,
            hasOtherActiveStylusPointer = hasOtherActiveStylus,
            lastStylusTimestampMs = lastStylusActivityTimestampMs
        )

        // 2. HOVER
        if (batch.action == InputActionKind.HOVER) {
            val lastIdx = batch.realSampleCount - 1
            val hoverDocPt = ctx.camera.viewToDoc(
                ViewPoint(batch.realX(lastIdx).toDouble(), batch.realY(lastIdx).toDouble())
            )
            return Outcome.Success(
                InkOutput(
                    pointerId = batch.pointerId,
                    strokeEpoch = 0L,
                    effectiveToolMode = classifiedMode,
                    capabilityReport = batch.capabilityReport,
                    hoverOverlay = EphemeralOverlayItem.HoverCursor(
                        point = hoverDocPt,
                        radiusPt = max(3.0, ctx.brush.baseWidthPt)
                    )
                )
            )
        }

        // 3. PALM_REJECTED
        if (classifiedMode == EffectiveInkToolMode.PALM_REJECTED) {
            val removed = activePointers.remove(batch.pointerId)
            if (removed != null) {
                removed.residentSamples.clear()
                spoolSink.discardSpooledChunks(removed.strokeId)
            }
            navigationAnchors.remove(batch.pointerId)
            return Outcome.Success(
                InkOutput(
                    pointerId = batch.pointerId,
                    strokeEpoch = 0L,
                    effectiveToolMode = EffectiveInkToolMode.PALM_REJECTED,
                    capabilityReport = batch.capabilityReport
                )
            )
        }

        // 4. NAVIGATION (stylusOnlyDrawing = true and FINGER touch)
        if (classifiedMode == EffectiveInkToolMode.NAVIGATION) {
            val lastIdx = batch.realSampleCount - 1
            val currentDocPt = ctx.camera.viewToDoc(
                ViewPoint(batch.realX(lastIdx).toDouble(), batch.realY(lastIdx).toDouble())
            )
            return when (batch.action) {
                InputActionKind.DOWN -> {
                    val firstDocPt = ctx.camera.viewToDoc(
                        ViewPoint(batch.realX(0).toDouble(), batch.realY(0).toDouble())
                    )
                    navigationAnchors[batch.pointerId] = currentDocPt
                    val delta = if (batch.realSampleCount > 1) {
                        DocPoint(currentDocPt.x - firstDocPt.x, currentDocPt.y - firstDocPt.y)
                    } else {
                        null
                    }
                    Outcome.Success(
                        InkOutput(
                            pointerId = batch.pointerId,
                            strokeEpoch = 0L,
                            effectiveToolMode = EffectiveInkToolMode.NAVIGATION,
                            capabilityReport = batch.capabilityReport,
                            navigationDeltaDoc = delta
                        )
                    )
                }
                InputActionKind.MOVE -> {
                    val prev = navigationAnchors.put(batch.pointerId, currentDocPt) ?: currentDocPt
                    Outcome.Success(
                        InkOutput(
                            pointerId = batch.pointerId,
                            strokeEpoch = 0L,
                            effectiveToolMode = EffectiveInkToolMode.NAVIGATION,
                            capabilityReport = batch.capabilityReport,
                            navigationDeltaDoc = DocPoint(currentDocPt.x - prev.x, currentDocPt.y - prev.y)
                        )
                    )
                }
                InputActionKind.UP -> {
                    val prev = navigationAnchors.remove(batch.pointerId) ?: currentDocPt
                    Outcome.Success(
                        InkOutput(
                            pointerId = batch.pointerId,
                            strokeEpoch = 0L,
                            effectiveToolMode = EffectiveInkToolMode.NAVIGATION,
                            capabilityReport = batch.capabilityReport,
                            navigationDeltaDoc = DocPoint(currentDocPt.x - prev.x, currentDocPt.y - prev.y)
                        )
                    )
                }
                else -> Outcome.Success(
                    InkOutput(
                        pointerId = batch.pointerId,
                        strokeEpoch = 0L,
                        effectiveToolMode = EffectiveInkToolMode.NAVIGATION,
                        capabilityReport = batch.capabilityReport
                    )
                )
            }
        }

        // 5. DRAW / TEMPORARY_ERASER stroke lifecycle (DOWN -> MOVE -> UP)
        val state = if (batch.action == InputActionKind.DOWN || existingState == null) {
            // If a previous state existed on this pointerId without UP, discard its provisional spool
            if (existingState != null) {
                existingState.residentSamples.clear()
                spoolSink.discardSpooledChunks(existingState.strokeId)
            }
            val epoch = strokeEpochGen.getAndIncrement()
            val firstDocPt = ctx.camera.viewToDoc(
                ViewPoint(batch.realX(0).toDouble(), batch.realY(0).toDouble())
            )
            ActivePointerStrokeState(
                pointerId = batch.pointerId,
                strokeEpoch = epoch,
                strokeId = ObjectId("stroke-p${batch.pointerId}-e$epoch"),
                gestureId = GestureId("gesture-p${batch.pointerId}-e$epoch"),
                mode = classifiedMode,
                currentChunkOrigin = firstDocPt,
                lastSequenceNumber = batch.sequenceNumber,
                lastTimestampMs = batch.realTimestampMs(0),
                lastDocPoint = firstDocPt
            ).also {
                activePointers[batch.pointerId] = it
            }
        } else {
            if (batch.sequenceNumber <= existingState.lastSequenceNumber) {
                return Outcome.Failure(
                    InputError.StaleSequence(
                        pointerId = batch.pointerId,
                        expectedMinSeq = existingState.lastSequenceNumber + 1L,
                        actualSeq = batch.sequenceNumber
                    )
                )
            }
            existingState.lastSequenceNumber = batch.sequenceNumber
            existingState
        }

        val appendOutcome = state.appendBatchSamples(batch, ctx, spoolSink)
        if (appendOutcome is Outcome.Failure) {
            activePointers.remove(batch.pointerId)
            return appendOutcome
        }

        return if (batch.action == InputActionKind.UP) {
            activePointers.remove(batch.pointerId)
            val planOutcome = if (state.mode == EffectiveInkToolMode.DRAW) {
                state.buildCommitPlan(ctx, spoolSink)
            } else {
                // TEMPORARY_ERASER clears any spooled chunks and returns null commitPlan here (eraser plans belong to F07)
                spoolSink.discardSpooledChunks(state.strokeId)
                Outcome.Success(null)
            }
            when (planOutcome) {
                is Outcome.Failure -> planOutcome
                is Outcome.Success -> Outcome.Success(
                    InkOutput(
                        pointerId = batch.pointerId,
                        strokeEpoch = state.strokeEpoch,
                        effectiveToolMode = state.mode,
                        capabilityReport = batch.capabilityReport,
                        draftStroke = null,
                        predictedOverlay = null,
                        commitPlan = planOutcome.value,
                        spooledChunkRefs = state.spooledRefs,
                        residentWindowBytes = 0L,
                        residentSampleCount = 0,
                        totalRealSamplesIngested = state.totalRealSamplesIngested
                    )
                )
            }
        } else {
            val draft = state.buildDraftItem(ctx)
            val predOverlay = state.buildPredictionOverlay(batch, ctx)
            Outcome.Success(
                InkOutput(
                    pointerId = batch.pointerId,
                    strokeEpoch = state.strokeEpoch,
                    effectiveToolMode = state.mode,
                    capabilityReport = batch.capabilityReport,
                    draftStroke = draft,
                    predictedOverlay = predOverlay,
                    commitPlan = null,
                    spooledChunkRefs = state.spooledRefs,
                    residentWindowBytes = state.currentResidentBytes(),
                    residentSampleCount = state.residentSamples.size,
                    totalRealSamplesIngested = state.totalRealSamplesIngested
                )
            )
        }
    }
}
