package inkspace.ink

import inkspace.core.error.InputError
import inkspace.core.id.GestureId
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.ink.EffectiveInkToolMode
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputContext
import inkspace.core.ink.SpooledStrokeChunk
import inkspace.core.ink.StagedChunkRef
import inkspace.core.ink.StrokeSpoolSink
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.ObjectStyle
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.Mutation
import inkspace.core.outcome.Outcome
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.render.DraftStrokeSceneItem
import inkspace.core.render.EphemeralOverlayItem
import inkspace.core.runtime.ImmutableInputBatch
import inkspace.core.unit.DocPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Default in-memory provisional staging implementation of [StrokeSpoolSink] with simulated DiskFull test hook.
 */
class InMemoryStrokeSpoolSink : StrokeSpoolSink {
    private val chunksByStroke = LinkedHashMap<ObjectId, MutableList<SpooledStrokeChunk>>()

    var failOnNextSpool: Boolean = false

    @Synchronized
    override fun spoolChunk(
        strokeId: ObjectId,
        chunkIndex: Int,
        origin: DocPoint,
        samples: List<StrokeSamplePoint>,
        byteSize: Int
    ): Outcome<StagedChunkRef, InputError> {
        if (failOnNextSpool) {
            failOnNextSpool = false
            return Outcome.Failure(
                InputError.SpoolFailed(
                    strokeId = strokeId.value,
                    reason = "Simulated ENOSPC / DiskFull while spooling provisional stroke chunk $chunkIndex"
                )
            )
        }
        val ref = StagedChunkRef(
            strokeId = strokeId,
            chunkIndex = chunkIndex,
            sampleCount = samples.size,
            byteSize = byteSize,
            origin = origin
        )
        val list = chunksByStroke.getOrPut(strokeId) { ArrayList() }
        list.add(SpooledStrokeChunk(ref = ref, samples = samples))
        return Outcome.Success(ref)
    }

    @Synchronized
    override fun readSpooledChunks(refs: List<StagedChunkRef>): Outcome<List<SpooledStrokeChunk>, InputError> {
        if (refs.isEmpty()) return Outcome.Success(emptyList())
        val strokeId = refs.first().strokeId
        val stored = chunksByStroke[strokeId]
            ?: return Outcome.Failure(
                InputError.SpoolFailed(strokeId.value, "Missing spooled chunks for ${strokeId.value}")
            )
        return Outcome.Success(java.util.List.copyOf(stored))
    }

    @Synchronized
    override fun discardSpooledChunks(strokeId: ObjectId) {
        chunksByStroke.remove(strokeId)
    }

    @Synchronized
    fun activeSpooledStrokeCount(): Int = chunksByStroke.size
}

/**
 * Per-pointer active stroke state owned by [InkEngine].
 * Maintains a bounded resident sample window (`<= maxResidentWindowBytes`, default `8 MiB`)
 * and spools older chunks to [StrokeSpoolSink] when the window or `±8192 pt` local origin envelope is exceeded.
 */
internal class ActivePointerStrokeState(
    val pointerId: Int,
    val strokeEpoch: Long,
    val strokeId: ObjectId,
    val gestureId: GestureId,
    val mode: EffectiveInkToolMode,
    var currentChunkOrigin: DocPoint,
    var lastSequenceNumber: Long,
    var lastTimestampMs: Long,
    var lastDocPoint: DocPoint
) {
    val residentSamples = ArrayList<StrokeSamplePoint>(128)
    val spooledRefs = ArrayList<StagedChunkRef>()
    var totalRealSamplesIngested: Int = 0

    fun currentResidentBytes(): Long {
        return residentSamples.size.toLong() * ImmutableInputBatch.BYTES_PER_SAMPLE.toLong()
    }

    fun appendBatchSamples(
        batch: InputBatch,
        ctx: InputContext,
        spoolSink: StrokeSpoolSink
    ): Outcome<Unit, InputError> {
        for (i in 0 until batch.realSampleCount) {
            val viewX = batch.realX(i).toDouble()
            val viewY = batch.realY(i).toDouble()
            val docPt = ctx.camera.viewToDoc(inkspace.core.unit.ViewPoint(viewX, viewY))
            val ts = batch.realTimestampMs(i)
            val dt = if (totalRealSamplesIngested == 0) {
                0
            } else {
                max(0L, ts - lastTimestampMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            lastTimestampMs = max(lastTimestampMs, ts)
            lastDocPoint = docPt

            var relX = docPt.x - currentChunkOrigin.x
            var relY = docPt.y - currentChunkOrigin.y

            val projectedBytes = (residentSamples.size + 1).toLong() * ImmutableInputBatch.BYTES_PER_SAMPLE.toLong()
            val exceedsOriginEnvelope = abs(relX) > CornerPreservingSmoother.MAX_LOCAL_CHUNK_OFFSET_PT ||
                abs(relY) > CornerPreservingSmoother.MAX_LOCAL_CHUNK_OFFSET_PT
            val exceedsResidentCap = residentSamples.size >= ctx.maxSamplesPerResidentChunk ||
                projectedBytes > ctx.maxResidentWindowBytes

            if (residentSamples.isNotEmpty() && (exceedsOriginEnvelope || exceedsResidentCap)) {
                val chunkBytes = residentSamples.size * ImmutableInputBatch.BYTES_PER_SAMPLE
                val spoolRes = spoolSink.spoolChunk(
                    strokeId = strokeId,
                    chunkIndex = spooledRefs.size,
                    origin = currentChunkOrigin,
                    samples = java.util.List.copyOf(residentSamples),
                    byteSize = chunkBytes
                )
                when (spoolRes) {
                    is Outcome.Failure -> {
                        residentSamples.clear()
                        spoolSink.discardSpooledChunks(strokeId)
                        return spoolRes
                    }
                    is Outcome.Success -> {
                        spooledRefs.add(spoolRes.value)
                        residentSamples.clear()
                        currentChunkOrigin = docPt
                        relX = 0.0
                        relY = 0.0
                    }
                }
            }

            residentSamples.add(
                StrokeSamplePoint(
                    relX = relX.toFloat(),
                    relY = relY.toFloat(),
                    pressure = batch.realPressure(i),
                    tilt = batch.realTilt(i),
                    timeDeltaMs = dt
                )
            )
            totalRealSamplesIngested += 1
        }
        return Outcome.Success(Unit)
    }

    fun buildDraftItem(ctx: InputContext): DraftStrokeSceneItem {
        val brush = ctx.brush
        val payload = StrokePayload(
            brushId = brush.toBrushIdString(),
            color = BrushPipeline.modulateColorAlpha(brush.colorArgb, brush),
            baseWidthPt = brush.baseWidthPt,
            origin = currentChunkOrigin,
            samples = java.util.List.copyOf(residentSamples),
            smoothingVersion = brush.smoothingVersion
        )
        val bounds = BrushPipeline.computeBounds(currentChunkOrigin, residentSamples, brush.baseWidthPt)
        return DraftStrokeSceneItem(
            draftId = strokeId,
            spaceId = ctx.activeSpaceId,
            layerId = ctx.activeLayerId,
            bounds = bounds,
            payload = payload,
            transform = AffineTransform2D.IDENTITY,
            style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = strokeEpoch)
        )
    }

    fun buildPredictionOverlay(batch: InputBatch, ctx: InputContext): EphemeralOverlayItem.LaserTrail? {
        if (batch.predictedSampleCount <= 0) return null
        val origin = lastDocPoint
        val relPoints = ArrayList<DocPoint>(batch.predictedSampleCount + 1)
        relPoints.add(DocPoint(0.0, 0.0))
        for (i in 0 until batch.predictedSampleCount) {
            val docPt = ctx.camera.viewToDoc(
                inkspace.core.unit.ViewPoint(batch.predictedX(i).toDouble(), batch.predictedY(i).toDouble())
            )
            relPoints.add(DocPoint(docPt.x - origin.x, docPt.y - origin.y))
        }
        return EphemeralOverlayItem.LaserTrail(
            origin = origin,
            relativePoints = relPoints,
            colorArgb = BrushPipeline.modulateColorAlpha(ctx.brush.colorArgb, ctx.brush),
            widthPt = ctx.brush.baseWidthPt
        )
    }

    fun buildCommitPlan(
        ctx: InputContext,
        spoolSink: StrokeSpoolSink
    ): Outcome<EditPlan, InputError> {
        val spooledOutcome = spoolSink.readSpooledChunks(spooledRefs)
        val spooledChunks = when (spooledOutcome) {
            is Outcome.Failure -> return spooledOutcome
            is Outcome.Success -> spooledOutcome.value
        }

        val brush = ctx.brush
        val modulatedColor = BrushPipeline.modulateColorAlpha(brush.colorArgb, brush)
        val brushIdStr = brush.toBrushIdString()
        val mutations = ArrayList<Mutation>(spooledChunks.size + 1)

        for ((idx, chunk) in spooledChunks.withIndex()) {
            val chunkObjId = if (spooledChunks.isEmpty()) strokeId else ObjectId("${strokeId.value}-c$idx")
            val payload = StrokePayload(
                brushId = brushIdStr,
                color = modulatedColor,
                baseWidthPt = brush.baseWidthPt,
                origin = chunk.ref.origin,
                samples = chunk.frozenSamples,
                smoothingVersion = brush.smoothingVersion
            )
            val bounds = BrushPipeline.computeBounds(chunk.ref.origin, chunk.frozenSamples, brush.baseWidthPt)
            mutations.add(
                InsertObject(
                    space = ctx.activeSpaceId,
                    layer = ctx.activeLayerId,
                    id = chunkObjId,
                    payload = payload,
                    bounds = bounds,
                    style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = strokeEpoch * 1000L + idx)
                )
            )
        }

        if (residentSamples.isNotEmpty()) {
            val tailObjId = if (spooledChunks.isEmpty()) {
                strokeId
            } else {
                ObjectId("${strokeId.value}-c${spooledChunks.size}")
            }
            val frozenTail = java.util.List.copyOf(residentSamples)
            val payload = StrokePayload(
                brushId = brushIdStr,
                color = modulatedColor,
                baseWidthPt = brush.baseWidthPt,
                origin = currentChunkOrigin,
                samples = frozenTail,
                smoothingVersion = brush.smoothingVersion
            )
            val bounds = BrushPipeline.computeBounds(currentChunkOrigin, frozenTail, brush.baseWidthPt)
            mutations.add(
                InsertObject(
                    space = ctx.activeSpaceId,
                    layer = ctx.activeLayerId,
                    id = tailObjId,
                    payload = payload,
                    bounds = bounds,
                    style = ObjectStyle(
                        opacity = 1.0f,
                        isVisible = true,
                        zOrder = strokeEpoch * 1000L + spooledChunks.size
                    )
                )
            )
        }

        spoolSink.discardSpooledChunks(strokeId)

        return Outcome.Success(
            EditPlan(
                base = ctx.docVersion,
                mutations = mutations,
                gesture = gestureId,
                label = UndoLabel("ink-stroke")
            )
        )
    }
}
