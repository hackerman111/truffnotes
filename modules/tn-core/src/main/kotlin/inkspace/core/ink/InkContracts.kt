package inkspace.core.ink

import inkspace.core.error.InputError
import inkspace.core.error.SubmitError
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.SpaceId
import inkspace.core.model.DocVersion
import inkspace.core.model.SessionKey
import inkspace.core.mutation.EditPlan
import inkspace.core.outcome.Outcome
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.render.DraftStrokeSceneItem
import inkspace.core.render.EphemeralOverlayItem
import inkspace.core.runtime.ImmutableInputBatch
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.MemoryCategory
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.MAX_WORLD_COORDINATE_PT
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

/**
 * Authoritative hardware-reported input sample.
 * Distinct at the type level from [PredictedSample] so predicted points can never enter committed strokes,
 * persistence, or Undo history.
 */
@Serializable
data class RealSample(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val tilt: Float = 0.0f,
    val timestampMs: Long = 0L
) {
    init {
        require(x.isFinite() && y.isFinite() && pressure.isFinite() && tilt.isFinite()) {
            "RealSample values must be finite"
        }
        require(pressure in 0.0f..1.0f) { "RealSample pressure must be in [0, 1]: $pressure" }
    }
}

/**
 * Ephemeral motion-predicted sample used exclusively for transient display latency reduction.
 * Never persisted, never included in EditPlan, and always replaced by subsequent [RealSample] batches.
 */
@Serializable
data class PredictedSample(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val tilt: Float = 0.0f,
    val timestampMs: Long = 0L
) {
    init {
        require(x.isFinite() && y.isFinite() && pressure.isFinite() && tilt.isFinite()) {
            "PredictedSample values must be finite"
        }
        require(pressure in 0.0f..1.0f) { "PredictedSample pressure must be in [0, 1]: $pressure" }
    }
}

@Serializable
enum class InputToolType {
    STYLUS,
    ERASER,
    FINGER,
    MOUSE,
    UNKNOWN
}

@Serializable
data class InputCapabilityReport(
    val toolType: InputToolType,
    val supportsPressure: Boolean,
    val supportsTilt: Boolean,
    val isPassiveTouch: Boolean
)

/**
 * Immutable input batch handed off to `InkEngine.ingest(batch, ctx)`.
 * Stores primitive arrays internally without exposing mutable backing references after handoff,
 * and strictly separates [RealSample] from [PredictedSample].
 */
class InputBatch private constructor(
    val session: SessionKey,
    val pointerId: Int,
    val sequenceNumber: Long,
    val action: InputActionKind,
    val toolType: InputToolType,
    val supportsPressure: Boolean,
    val supportsTilt: Boolean,
    val primaryButtonPressed: Boolean,
    val secondaryEraserButtonPressed: Boolean,
    val isFlagCanceled: Boolean,
    val realSampleCount: Int,
    val predictedSampleCount: Int,
    val byteSize: Int,
    private val realXs: FloatArray,
    private val realYs: FloatArray,
    private val realPressures: FloatArray,
    private val realTilts: FloatArray,
    private val realTimestampsMs: LongArray,
    private val predXs: FloatArray,
    private val predYs: FloatArray,
    private val predPressures: FloatArray,
    private val predTilts: FloatArray,
    private val predTimestampsMs: LongArray
) {
    val isEffectiveCancel: Boolean
        get() = action == InputActionKind.CANCEL || isFlagCanceled

    val capabilityReport: InputCapabilityReport
        get() = InputCapabilityReport(
            toolType = toolType,
            supportsPressure = supportsPressure,
            supportsTilt = supportsTilt,
            isPassiveTouch = toolType == InputToolType.FINGER
        )

    fun realX(index: Int): Float {
        require(index in 0 until realSampleCount) { "Real sample index $index out of [0, $realSampleCount)" }
        return realXs[index]
    }

    fun realY(index: Int): Float {
        require(index in 0 until realSampleCount) { "Real sample index $index out of [0, $realSampleCount)" }
        return realYs[index]
    }

    fun realPressure(index: Int): Float {
        require(index in 0 until realSampleCount) { "Real sample index $index out of [0, $realSampleCount)" }
        return realPressures[index]
    }

    fun realTilt(index: Int): Float {
        require(index in 0 until realSampleCount) { "Real sample index $index out of [0, $realSampleCount)" }
        return realTilts[index]
    }

    fun realTimestampMs(index: Int): Long {
        require(index in 0 until realSampleCount) { "Real sample index $index out of [0, $realSampleCount)" }
        return realTimestampsMs[index]
    }

    fun realSampleAt(index: Int): RealSample {
        require(index in 0 until realSampleCount) { "Real sample index $index out of [0, $realSampleCount)" }
        return RealSample(
            x = realXs[index],
            y = realYs[index],
            pressure = realPressures[index],
            tilt = realTilts[index],
            timestampMs = realTimestampsMs[index]
        )
    }

    fun predictedX(index: Int): Float {
        require(index in 0 until predictedSampleCount) {
            "Predicted sample index $index out of [0, $predictedSampleCount)"
        }
        return predXs[index]
    }

    fun predictedY(index: Int): Float {
        require(index in 0 until predictedSampleCount) {
            "Predicted sample index $index out of [0, $predictedSampleCount)"
        }
        return predYs[index]
    }

    fun predictedSampleAt(index: Int): PredictedSample {
        require(index in 0 until predictedSampleCount) {
            "Predicted sample index $index out of [0, $predictedSampleCount)"
        }
        return PredictedSample(
            x = predXs[index],
            y = predYs[index],
            pressure = predPressures[index],
            tilt = predTilts[index],
            timestampMs = predTimestampsMs[index]
        )
    }

    fun toImmutableInputBatch(maxBatchBytes: Int = 64 * 1024): Outcome<ImmutableInputBatch, SubmitError> {
        val effectiveAction = if (isFlagCanceled) InputActionKind.CANCEL else action
        return ImmutableInputBatch.copyFromArrays(
            session = session,
            pointerId = pointerId,
            sequenceNumber = sequenceNumber,
            action = effectiveAction,
            count = realSampleCount,
            xs = realXs,
            ys = realYs,
            pressures = realPressures,
            tilts = realTilts,
            timestampsMs = realTimestampsMs,
            maxBatchBytes = maxBatchBytes
        )
    }

    companion object {
        fun fromImmutableBatch(
            batch: ImmutableInputBatch,
            toolType: InputToolType = InputToolType.STYLUS,
            supportsPressure: Boolean = true,
            supportsTilt: Boolean = true,
            primaryButtonPressed: Boolean = false,
            secondaryEraserButtonPressed: Boolean = false,
            isFlagCanceled: Boolean = false,
            predictedSamples: List<PredictedSample> = emptyList()
        ): InputBatch {
            val count = batch.sampleCount
            val rXs = FloatArray(count)
            val rYs = FloatArray(count)
            val rPs = FloatArray(count)
            val rTs = FloatArray(count)
            val rTime = LongArray(count)
            for (i in 0 until count) {
                rXs[i] = batch.sampleX(i)
                rYs[i] = batch.sampleY(i)
                rPs[i] = if (supportsPressure) batch.samplePressure(i) else 0.5f
                rTs[i] = if (supportsTilt) batch.sampleTilt(i) else 0.0f
                rTime[i] = batch.sampleTimestampMs(i)
            }
            val pCount = predictedSamples.size
            val pXs = FloatArray(pCount)
            val pYs = FloatArray(pCount)
            val pPs = FloatArray(pCount)
            val pTs = FloatArray(pCount)
            val pTime = LongArray(pCount)
            for (i in 0 until pCount) {
                val p = predictedSamples[i]
                pXs[i] = p.x
                pYs[i] = p.y
                pPs[i] = if (supportsPressure) p.pressure else 0.5f
                pTs[i] = if (supportsTilt) p.tilt else 0.0f
                pTime[i] = p.timestampMs
            }
            val totalBytes = ImmutableInputBatch.BATCH_HEADER_BYTES +
                (count + pCount) * ImmutableInputBatch.BYTES_PER_SAMPLE
            return InputBatch(
                session = batch.session,
                pointerId = batch.pointerId,
                sequenceNumber = batch.sequenceNumber,
                action = batch.action,
                toolType = toolType,
                supportsPressure = supportsPressure,
                supportsTilt = supportsTilt,
                primaryButtonPressed = primaryButtonPressed,
                secondaryEraserButtonPressed = secondaryEraserButtonPressed,
                isFlagCanceled = isFlagCanceled,
                realSampleCount = count,
                predictedSampleCount = pCount,
                byteSize = totalBytes,
                realXs = rXs,
                realYs = rYs,
                realPressures = rPs,
                realTilts = rTs,
                realTimestampsMs = rTime,
                predXs = pXs,
                predYs = pYs,
                predPressures = pPs,
                predTilts = pTs,
                predTimestampsMs = pTime
            )
        }

        fun fromSamples(
            session: SessionKey,
            pointerId: Int,
            sequenceNumber: Long,
            action: InputActionKind,
            realSamples: List<RealSample>,
            predictedSamples: List<PredictedSample> = emptyList(),
            toolType: InputToolType = InputToolType.STYLUS,
            supportsPressure: Boolean = true,
            supportsTilt: Boolean = true,
            primaryButtonPressed: Boolean = false,
            secondaryEraserButtonPressed: Boolean = false,
            isFlagCanceled: Boolean = false,
            maxBatchBytes: Int = 64 * 1024
        ): Outcome<InputBatch, InputError> {
            if (sequenceNumber < 0L) {
                return Outcome.Failure(InputError.InvalidSample(0, "Negative sequenceNumber: $sequenceNumber"))
            }
            if (realSamples.isEmpty()) {
                return Outcome.Failure(InputError.InvalidSample(0, "InputBatch must contain at least 1 RealSample"))
            }
            val rCount = realSamples.size
            val pCount = predictedSamples.size
            val totalBytes = ImmutableInputBatch.BATCH_HEADER_BYTES +
                (rCount + pCount) * ImmutableInputBatch.BYTES_PER_SAMPLE
            if (totalBytes > maxBatchBytes) {
                return Outcome.Failure(
                    InputError.MemoryWindowExceeded(
                        requestedBytes = totalBytes.toLong(),
                        maxWindowBytes = maxBatchBytes.toLong()
                    )
                )
            }

            val rXs = FloatArray(rCount)
            val rYs = FloatArray(rCount)
            val rPs = FloatArray(rCount)
            val rTs = FloatArray(rCount)
            val rTime = LongArray(rCount)
            var prevTime = Long.MIN_VALUE
            for (i in 0 until rCount) {
                val s = realSamples[i]
                if (abs(s.x.toDouble()) > MAX_WORLD_COORDINATE_PT || abs(s.y.toDouble()) > MAX_WORLD_COORDINATE_PT) {
                    return Outcome.Failure(InputError.InvalidSample(i, "Sample exceeds world envelope"))
                }
                if (s.timestampMs < prevTime) {
                    return Outcome.Failure(
                        InputError.InvalidSample(i, "Non-monotonic timestamp: ${s.timestampMs} < $prevTime")
                    )
                }
                prevTime = s.timestampMs
                rXs[i] = s.x
                rYs[i] = s.y
                rPs[i] = if (supportsPressure) s.pressure else 0.5f
                rTs[i] = if (supportsTilt) s.tilt else 0.0f
                rTime[i] = s.timestampMs
            }

            val pXs = FloatArray(pCount)
            val pYs = FloatArray(pCount)
            val pPs = FloatArray(pCount)
            val pTs = FloatArray(pCount)
            val pTime = LongArray(pCount)
            for (i in 0 until pCount) {
                val p = predictedSamples[i]
                pXs[i] = p.x
                pYs[i] = p.y
                pPs[i] = if (supportsPressure) p.pressure else 0.5f
                pTs[i] = if (supportsTilt) p.tilt else 0.0f
                pTime[i] = p.timestampMs
            }

            return Outcome.Success(
                InputBatch(
                    session = session,
                    pointerId = pointerId,
                    sequenceNumber = sequenceNumber,
                    action = action,
                    toolType = toolType,
                    supportsPressure = supportsPressure,
                    supportsTilt = supportsTilt,
                    primaryButtonPressed = primaryButtonPressed,
                    secondaryEraserButtonPressed = secondaryEraserButtonPressed,
                    isFlagCanceled = isFlagCanceled,
                    realSampleCount = rCount,
                    predictedSampleCount = pCount,
                    byteSize = totalBytes,
                    realXs = rXs,
                    realYs = rYs,
                    realPressures = rPs,
                    realTilts = rTs,
                    realTimestampsMs = rTime,
                    predXs = pXs,
                    predYs = pYs,
                    predPressures = pPs,
                    predTilts = pTs,
                    predTimestampsMs = pTime
                )
            )
        }
    }
}

@Serializable
enum class BrushKind(val canonicalId: String) {
    BALL("ball"),
    FOUNTAIN("fountain"),
    PENCIL("pencil"),
    BRUSH("brush"),
    HIGHLIGHTER("highlighter");

    companion object {
        fun fromBrushId(brushId: String): BrushKind {
            val prefix = brushId.substringBefore(':').lowercase(Locale.US)
            return entries.firstOrNull { it.canonicalId == prefix } ?: BALL
        }
    }
}

@Serializable
enum class StrokeEndCap {
    ROUND,
    FLAT,
    TAPERED
}

@Serializable
enum class StrokeLinePattern {
    SOLID,
    DASHED,
    DOTTED
}

@Serializable
data class BrushSpec(
    val kind: BrushKind = BrushKind.BALL,
    val colorArgb: Int = 0xFF000000.toInt(),
    val baseWidthPt: Double = 2.0,
    val opacity: Float = 1.0f,
    val smoothingVersion: Int = 1,
    val smoothingStrength: Float = 0.5f,
    val pressureSensitivity: Float = 0.6f,
    val tiltSensitivity: Float = 0.3f,
    val endCap: StrokeEndCap = when (kind) {
        BrushKind.BALL -> StrokeEndCap.ROUND
        BrushKind.FOUNTAIN -> StrokeEndCap.TAPERED
        BrushKind.BRUSH -> StrokeEndCap.TAPERED
        BrushKind.PENCIL -> StrokeEndCap.ROUND
        BrushKind.HIGHLIGHTER -> StrokeEndCap.FLAT
    },
    val linePattern: StrokeLinePattern = StrokeLinePattern.SOLID
) {
    init {
        require(baseWidthPt > 0.0 && baseWidthPt.isFinite()) { "baseWidthPt must be positive finite: $baseWidthPt" }
        require(opacity in 0.0f..1.0f) { "opacity must be in [0, 1]: $opacity" }
        require(smoothingVersion >= 1) { "smoothingVersion must be >= 1" }
        require(smoothingStrength in 0.0f..1.0f) { "smoothingStrength must be in [0, 1]" }
        require(pressureSensitivity in 0.0f..2.0f) { "pressureSensitivity must be in [0, 2]" }
        require(tiltSensitivity in 0.0f..2.0f) { "tiltSensitivity must be in [0, 2]" }
    }

    fun toBrushIdString(): String {
        if (kind == BrushKind.HIGHLIGHTER && linePattern == StrokeLinePattern.SOLID) {
            return "highlighter"
        }
        if (linePattern == StrokeLinePattern.SOLID &&
            smoothingStrength == 0.5f &&
            pressureSensitivity == 0.6f &&
            tiltSensitivity == 0.3f
        ) {
            return kind.canonicalId
        }
        return String.format(
            Locale.US,
            "%s:cap=%s:pat=%s:sm=%.2f:ps=%.2f:ts=%.2f",
            kind.canonicalId,
            endCap.name,
            linePattern.name,
            smoothingStrength,
            pressureSensitivity,
            tiltSensitivity
        )
    }

    companion object {
        fun fromBrushIdString(
            brushId: String,
            colorArgb: Int = 0xFF000000.toInt(),
            baseWidthPt: Double = 2.0,
            smoothingVersion: Int = 1
        ): BrushSpec {
            val kind = BrushKind.fromBrushId(brushId)
            val parts = brushId.split(':')
            var cap = when (kind) {
                BrushKind.BALL -> StrokeEndCap.ROUND
                BrushKind.FOUNTAIN -> StrokeEndCap.TAPERED
                BrushKind.BRUSH -> StrokeEndCap.TAPERED
                BrushKind.PENCIL -> StrokeEndCap.ROUND
                BrushKind.HIGHLIGHTER -> StrokeEndCap.FLAT
            }
            var pat = StrokeLinePattern.SOLID
            var sm = 0.5f
            var ps = when (kind) {
                BrushKind.BALL -> 0.15f
                BrushKind.FOUNTAIN -> 0.75f
                BrushKind.BRUSH -> 1.0f
                BrushKind.PENCIL -> 0.45f
                BrushKind.HIGHLIGHTER -> 0.1f
            }
            var ts = when (kind) {
                BrushKind.BALL -> 0.0f
                BrushKind.FOUNTAIN -> 0.4f
                BrushKind.BRUSH -> 0.6f
                BrushKind.PENCIL -> 0.8f
                BrushKind.HIGHLIGHTER -> 0.5f
            }
            for (i in 1 until parts.size) {
                val kv = parts[i].split('=', limit = 2)
                if (kv.size != 2) continue
                when (kv[0]) {
                    "cap" -> runCatching { StrokeEndCap.valueOf(kv[1]) }.getOrNull()?.let { cap = it }
                    "pat" -> runCatching { StrokeLinePattern.valueOf(kv[1]) }.getOrNull()?.let { pat = it }
                    "sm" -> kv[1].toFloatOrNull()?.coerceIn(0.0f, 1.0f)?.let { sm = it }
                    "ps" -> kv[1].toFloatOrNull()?.coerceIn(0.0f, 2.0f)?.let { ps = it }
                    "ts" -> kv[1].toFloatOrNull()?.coerceIn(0.0f, 2.0f)?.let { ts = it }
                }
            }
            return BrushSpec(
                kind = kind,
                colorArgb = colorArgb,
                baseWidthPt = baseWidthPt,
                smoothingVersion = smoothingVersion,
                smoothingStrength = sm,
                pressureSensitivity = ps,
                tiltSensitivity = ts,
                endCap = cap,
                linePattern = pat
            )
        }
    }
}

@Serializable
enum class ActiveToolKind {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPE,
    RULER,
    READ_ONLY
}

@Serializable
enum class StylusButtonAction {
    TEMPORARY_ERASER,
    LASSO,
    NONE
}

@Serializable
enum class GesturePriorityLevel(val precedence: Int) {
    SYSTEM_INTERCEPT(1),
    ACTIVE_OPERATION(2),
    EXPLICIT_TOOL(3),
    FINGER_NAVIGATION(4),
    STYLUS_GEOMETRIC_GESTURE(5),
    NORMAL_INK(6)
}

@Serializable
data class GestureSettings(
    val geometricGesturesEnabled: Boolean = true,
    val holdToShapeEnabled: Boolean = true,
    val holdThresholdMs: Long = 400L,
    val circleToLassoEnabled: Boolean = true,
    val circleReholdLineTolerancePt: Double = 24.0,
    val circleReholdThresholdMs: Long = 300L,
    val circleReholdWindowMs: Long = 1200L,
    val scribbleEraseEnabled: Boolean = true,
    val scribbleMinReversals: Int = 4,
    val scribbleAngleToleranceDeg: Double = 45.0,
    val scribbleEnvelopeTolerancePt: Double = 36.0,
    val twoFingerUndoEnabled: Boolean = true,
    val threeFingerRedoEnabled: Boolean = true,
    val multiTapMaxDurationMs: Long = 350L,
    val multiTapMaxDistancePt: Double = 20.0
) {
    fun resetToDefaults(): GestureSettings = GestureSettings()
}

@Serializable
data class MultiTapGestureEvent(
    val fingerCount: Int,
    val tapCount: Int,
    val positionDoc: DocPoint,
    val timestampMs: Long
)

sealed interface GestureProposal {
    data class ShapeHold(
        val strokeId: ObjectId,
        val shapeCandidate: inkspace.core.objects.ShapeCandidate.Recognized,
        val holdDurationMs: Long
    ) : GestureProposal

    data class CircleToLasso(
        val strokeId: ObjectId,
        val enclosedBounds: inkspace.core.unit.DocRect
    ) : GestureProposal

    data class ScribbleErase(
        val erasedStrokeIds: Set<ObjectId>,
        val envelope: inkspace.core.unit.DocRect
    ) : GestureProposal {
        val frozenErasedStrokeIds: Set<ObjectId> = java.util.Collections.unmodifiableSet(erasedStrokeIds)
    }

    data class MultiFingerUndoRedo(
        val isRedo: Boolean,
        val fingerCount: Int
    ) : GestureProposal

    data class Navigation(
        val deltaDoc: DocPoint
    ) : GestureProposal

    data class SystemIntercept(
        val reason: String
    ) : GestureProposal
}

sealed interface GestureDecision {
    data class ExecuteShape(val proposal: GestureProposal.ShapeHold) : GestureDecision
    data class ExecuteLassoSelection(val proposal: GestureProposal.CircleToLasso) : GestureDecision
    data class ExecuteScribbleErase(val proposal: GestureProposal.ScribbleErase) : GestureDecision
    data class ExecuteUndoRedo(val proposal: GestureProposal.MultiFingerUndoRedo) : GestureDecision
    data class ExecuteNavigation(val proposal: GestureProposal.Navigation) : GestureDecision
    data class ConsumeAsSystem(val proposal: GestureProposal.SystemIntercept) : GestureDecision
    object FallbackToInk : GestureDecision
    object Ignored : GestureDecision
}

@Serializable
enum class PalmPosture { ANY, LEFT_OF_PEN, RIGHT_OF_PEN, BELOW_PEN }

@Serializable
data class StylusInputPolicy(
    val stylusOnlyDrawing: Boolean = false,
    val palmRejectionEnabled: Boolean = true,
    val temporaryEraserButtonEnabled: Boolean = true,
    val palmRejectionWindowMs: Long = 300L,
    val palmPosture: PalmPosture = PalmPosture.ANY
)

@Serializable
enum class EffectiveInkToolMode {
    DRAW,
    TEMPORARY_ERASER,
    NAVIGATION,
    HOVER,
    PALM_REJECTED,
    CANCELED
}

@Serializable
data class StagedChunkRef(
    val strokeId: ObjectId,
    val chunkIndex: Int,
    val sampleCount: Int,
    val byteSize: Int,
    val origin: DocPoint
)

data class SpooledStrokeChunk(
    val ref: StagedChunkRef,
    val samples: List<StrokeSamplePoint>
) {
    val frozenSamples: List<StrokeSamplePoint> = java.util.List.copyOf(samples)
}

/**
 * Provisional staging port for long strokes whose sample stream exceeds the bounded resident window (`<= 8 MiB`).
 * Spools older chunks without creating a document revision; on `UP`, all chunks are published in one atomic Undo step,
 * and on `CANCEL`, provisional chunks are discarded and made collectible.
 */
interface StrokeSpoolSink {
    fun spoolChunk(
        strokeId: ObjectId,
        chunkIndex: Int,
        origin: DocPoint,
        samples: List<StrokeSamplePoint>,
        byteSize: Int
    ): Outcome<StagedChunkRef, InputError>

    fun readSpooledChunks(refs: List<StagedChunkRef>): Outcome<List<SpooledStrokeChunk>, InputError>

    fun discardSpooledChunks(strokeId: ObjectId)
}

data class InputContext(
    val docVersion: DocVersion,
    val activeSpaceId: SpaceId = SpaceId("space-1"),
    val activePageId: PageId = PageId("page-1"),
    val activeLayerId: LayerId = LayerId("layer-1"),
    val isLayerLocked: Boolean = false,
    val camera: CameraTransform = CameraTransform(),
    val brush: BrushSpec = BrushSpec(),
    val policy: StylusInputPolicy = StylusInputPolicy(),
    val gestureSettings: GestureSettings = GestureSettings(),
    val activeTool: ActiveToolKind = ActiveToolKind.PEN,
    val hasActiveOperation: Boolean = false,
    val isSystemGestureIntercepted: Boolean = false,
    val maxResidentWindowBytes: Long = MemoryCategory.DRAFT_STROKE_WINDOW.defaultCapBytes,
    val maxSamplesPerResidentChunk: Int = 2048
) {
    init {
        require(maxResidentWindowBytes > 0L) { "maxResidentWindowBytes must be positive" }
        require(maxSamplesPerResidentChunk > 0) { "maxSamplesPerResidentChunk must be positive" }
    }
}

/**
 * Output of `InkEngine.ingest(batch: InputBatch, ctx: InputContext)`.
 */
data class InkOutput(
    val pointerId: Int,
    val strokeEpoch: Long,
    val effectiveToolMode: EffectiveInkToolMode,
    val capabilityReport: InputCapabilityReport,
    val draftStroke: DraftStrokeSceneItem? = null,
    val predictedOverlay: EphemeralOverlayItem.LaserTrail? = null,
    val hoverOverlay: EphemeralOverlayItem.HoverCursor? = null,
    val navigationDeltaDoc: DocPoint? = null,
    val commitPlan: EditPlan? = null,
    val spooledChunkRefs: List<StagedChunkRef> = emptyList(),
    val gestureDecision: GestureDecision? = null,
    val holdDurationMs: Long = 0L,
    val residentWindowBytes: Long = 0L,
    val residentSampleCount: Int = 0,
    val totalRealSamplesIngested: Int = 0
) {
    val frozenSpooledChunkRefs: List<StagedChunkRef> = java.util.List.copyOf(spooledChunkRefs)
}
