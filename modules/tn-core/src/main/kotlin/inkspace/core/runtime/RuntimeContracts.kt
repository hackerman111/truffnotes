package inkspace.core.runtime

import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.RequestGeneration
import inkspace.core.id.ResizeGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SurfaceEpoch
import inkspace.core.limits.Limits
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.SessionKey
import inkspace.core.mutation.EditPlan
import inkspace.core.outcome.Outcome
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.JobSink
import inkspace.core.ports.PdfPort
import inkspace.core.ports.RenderPort
import inkspace.core.ports.RequestId
import inkspace.core.ports.ResourcePort
import inkspace.core.ports.StagedAssetRef
import inkspace.core.ports.StoragePort
import inkspace.core.ports.TextPort
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.MAX_WORLD_COORDINATE_PT
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class MemoryCategory(val defaultCapBytes: Long) {
    RESIDENT_MODEL_PAYLOAD(64L * 1024L * 1024L),
    UNDO_REDO_RESIDENT(32L * 1024L * 1024L),
    CPU_RASTER_TILES(32L * 1024L * 1024L),
    DERIVED_PATHS_LAYOUT_SPATIAL(32L * 1024L * 1024L),
    PDF_WORKING_SET(64L * 1024L * 1024L),
    INPUT_IPC_PERSISTENCE_QUEUES(16L * 1024L * 1024L),
    CONTROLLED_GRAPHICS_TARGETS(96L * 1024L * 1024L),
    DRAFT_STROKE_WINDOW(8L * 1024L * 1024L)
}

@Serializable
enum class MemoryPressureLevel {
    LOW,
    MODERATE,
    CRITICAL
}

@Serializable
enum class InputActionKind(val isTerminal: Boolean) {
    DOWN(false),
    MOVE(false),
    UP(true),
    CANCEL(true),
    HOVER(false)
}

@Serializable
enum class SaveStatusKind {
    SAVED,
    SAVING,
    UNSAVED_ERROR,
    PENDING_OVERFLOW
}

@Serializable
data class SaveStatus(
    val session: SessionKey,
    val status: SaveStatusKind,
    val appliedRevision: Revision,
    val durableRevision: Revision,
    val pendingCount: Int,
    val pendingBytes: Long,
    val lastError: String? = null
) {
    val hasUnsavedChanges: Boolean
        get() = appliedRevision > durableRevision || pendingCount > 0
}

@Serializable
data class ViewportState(
    val session: SessionKey,
    val camera: CameraTransform = CameraTransform(),
    val widthPx: Int = 1080,
    val heightPx: Int = 1920,
    val isLandscape: Boolean = false,
    val isReadOnly: Boolean = false,
    val isFocusMode: Boolean = false,
    val activePageId: PageId? = null,
    val selectedObjectIds: Set<ObjectId> = emptySet(),
    val surfaceEpoch: SurfaceEpoch = SurfaceEpoch(0L),
    val resizeGeneration: ResizeGeneration = ResizeGeneration(0L)
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Viewport dimensions must be positive: ($widthPx, $heightPx)" }
    }
}

/**
 * Encapsulated primitive sample batch handed off from platform input transport to engine.
 * Never exposes mutable FloatArray/LongArray backing references and never allocates per-sample Point objects.
 */
class ImmutableInputBatch private constructor(
    val session: SessionKey,
    val pointerId: Int,
    val sequenceNumber: Long,
    val action: InputActionKind,
    val sampleCount: Int,
    val byteSize: Int,
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val pressures: FloatArray,
    private val tilts: FloatArray,
    private val timestampsMs: LongArray
) {
    fun sampleX(index: Int): Float {
        require(index in 0 until sampleCount) { "Sample index $index out of bounds [0, $sampleCount)" }
        return xs[index]
    }

    fun sampleY(index: Int): Float {
        require(index in 0 until sampleCount) { "Sample index $index out of bounds [0, $sampleCount)" }
        return ys[index]
    }

    fun samplePressure(index: Int): Float {
        require(index in 0 until sampleCount) { "Sample index $index out of bounds [0, $sampleCount)" }
        return pressures[index]
    }

    fun sampleTilt(index: Int): Float {
        require(index in 0 until sampleCount) { "Sample index $index out of bounds [0, $sampleCount)" }
        return tilts[index]
    }

    fun sampleTimestampMs(index: Int): Long {
        require(index in 0 until sampleCount) { "Sample index $index out of bounds [0, $sampleCount)" }
        return timestampsMs[index]
    }

    fun coalesceMoveWith(next: ImmutableInputBatch, maxBatchBytes: Int): ImmutableInputBatch? {
        if (this.action != InputActionKind.MOVE || next.action != InputActionKind.MOVE) return null
        if (this.session != next.session || this.pointerId != next.pointerId) return null
        if (next.sequenceNumber <= this.sequenceNumber) return null
        val total = this.sampleCount + next.sampleCount
        val combinedBytes = BATCH_HEADER_BYTES + total * BYTES_PER_SAMPLE
        if (combinedBytes > maxBatchBytes) return null
        val mergedXs = FloatArray(total)
        val mergedYs = FloatArray(total)
        val mergedPressures = FloatArray(total)
        val mergedTilts = FloatArray(total)
        val mergedTimestamps = LongArray(total)

        System.arraycopy(this.xs, 0, mergedXs, 0, this.sampleCount)
        System.arraycopy(next.xs, 0, mergedXs, this.sampleCount, next.sampleCount)
        System.arraycopy(this.ys, 0, mergedYs, 0, this.sampleCount)
        System.arraycopy(next.ys, 0, mergedYs, this.sampleCount, next.sampleCount)
        System.arraycopy(this.pressures, 0, mergedPressures, 0, this.sampleCount)
        System.arraycopy(next.pressures, 0, mergedPressures, this.sampleCount, next.sampleCount)
        System.arraycopy(this.tilts, 0, mergedTilts, 0, this.sampleCount)
        System.arraycopy(next.tilts, 0, mergedTilts, this.sampleCount, next.sampleCount)
        System.arraycopy(this.timestampsMs, 0, mergedTimestamps, 0, this.sampleCount)
        System.arraycopy(next.timestampsMs, 0, mergedTimestamps, this.sampleCount, next.sampleCount)

        return ImmutableInputBatch(
            session = next.session,
            pointerId = next.pointerId,
            sequenceNumber = next.sequenceNumber,
            action = InputActionKind.MOVE,
            sampleCount = total,
            byteSize = combinedBytes,
            xs = mergedXs,
            ys = mergedYs,
            pressures = mergedPressures,
            tilts = mergedTilts,
            timestampsMs = mergedTimestamps
        )
    }

    companion object {
        const val BYTES_PER_SAMPLE: Int = 24
        const val BATCH_HEADER_BYTES: Int = 64

        fun copyFromArrays(
            session: SessionKey,
            pointerId: Int,
            sequenceNumber: Long,
            action: InputActionKind,
            count: Int,
            xs: FloatArray,
            ys: FloatArray,
            pressures: FloatArray,
            tilts: FloatArray,
            timestampsMs: LongArray,
            maxBatchBytes: Int = 64 * 1024
        ): Outcome<ImmutableInputBatch, SubmitError> {
            if (sequenceNumber < 0L) {
                return Outcome.Failure(SubmitError.InvalidPayload("Negative sequenceNumber: $sequenceNumber"))
            }
            if (count <= 0) {
                return Outcome.Failure(SubmitError.InvalidPayload("Input batch must contain at least 1 sample"))
            }
            if (xs.size < count || ys.size < count || pressures.size < count || tilts.size < count || timestampsMs.size < count) {
                return Outcome.Failure(SubmitError.InvalidPayload("Sample arrays smaller than count=$count"))
            }
            val computedBytes = BATCH_HEADER_BYTES + count * BYTES_PER_SAMPLE
            if (computedBytes > maxBatchBytes) {
                return Outcome.Failure(
                    SubmitError.ByteBudgetExceeded(
                        requestedBytes = computedBytes.toLong(),
                        currentBytes = 0L,
                        maxBytes = maxBatchBytes.toLong()
                    )
                )
            }
            var prevTime = Long.MIN_VALUE
            for (i in 0 until count) {
                val x = xs[i]
                val y = ys[i]
                val p = pressures[i]
                val t = tilts[i]
                val ts = timestampsMs[i]
                if (!x.isFinite() || !y.isFinite() || !p.isFinite() || !t.isFinite()) {
                    return Outcome.Failure(SubmitError.InvalidPayload("Non-finite sample at index $i"))
                }
                if (abs(x.toDouble()) > MAX_WORLD_COORDINATE_PT || abs(y.toDouble()) > MAX_WORLD_COORDINATE_PT) {
                    return Outcome.Failure(SubmitError.InvalidPayload("Sample coordinates exceed envelope at index $i"))
                }
                if (p < 0.0f || p > 1.0f) {
                    return Outcome.Failure(SubmitError.InvalidPayload("Sample pressure $p out of [0, 1] at index $i"))
                }
                if (ts < prevTime) {
                    return Outcome.Failure(SubmitError.InvalidPayload("Non-monotonic timestamp at index $i: $ts < $prevTime"))
                }
                prevTime = ts
            }

            return Outcome.Success(
                ImmutableInputBatch(
                    session = session,
                    pointerId = pointerId,
                    sequenceNumber = sequenceNumber,
                    action = action,
                    sampleCount = count,
                    byteSize = computedBytes,
                    xs = xs.copyOf(count),
                    ys = ys.copyOf(count),
                    pressures = pressures.copyOf(count),
                    tilts = tilts.copyOf(count),
                    timestampsMs = timestampsMs.copyOf(count)
                )
            )
        }
    }
}

@Serializable
data class RuntimeConfig(
    val storageRootPath: String,
    val maxWarmSessions: Int = 3,
    val maxRenderSnapshots: Int = 2,
    val workerThreads: Int = 2,
    val totalQueueByteCap: Long = 16L * 1024L * 1024L,
    val inputQueueMaxBytes: Long = 4L * 1024L * 1024L,
    val maxInputBatchBytes: Int = 64 * 1024,
    val terminalControlReservedSlots: Int = 64,
    val persistQueueMaxEnvelopes: Int = 256,
    val persistQueueMaxBytes: Long = 8L * 1024L * 1024L,
    val durableAckMaxSlots: Int = 256,
    val durableAckMaxBytes: Long = 256L * 1024L,
    val mainEventsMaxEnvelopes: Int = 256,
    val mainEventsMaxBytes: Long = 2L * 1024L * 1024L,
    val errorEventsMaxSlots: Int = 64,
    val errorEventsMaxBytes: Long = 128L * 1024L,
    val limits: Limits = Limits()
) {
    init {
        require(storageRootPath.isNotBlank()) { "storageRootPath cannot be blank" }
        require(maxWarmSessions in 1..16) { "maxWarmSessions must be in 1..16" }
        require(maxRenderSnapshots in 1..8) { "maxRenderSnapshots must be in 1..8" }
        require(workerThreads in 1..4) { "workerThreads must be in 1..4" }
        require(totalQueueByteCap > 0L) { "totalQueueByteCap must be positive" }
        require(inputQueueMaxBytes > 0L) { "inputQueueMaxBytes must be positive" }
        require(maxInputBatchBytes > 0) { "maxInputBatchBytes must be positive" }
        require(terminalControlReservedSlots > 0) { "terminalControlReservedSlots must be positive" }
        require(persistQueueMaxEnvelopes > 0) { "persistQueueMaxEnvelopes must be positive" }
        require(persistQueueMaxBytes > 0L) { "persistQueueMaxBytes must be positive" }
        require(durableAckMaxSlots > 0) { "durableAckMaxSlots must be positive" }
        require(durableAckMaxBytes > 0L) { "durableAckMaxBytes must be positive" }
        require(mainEventsMaxEnvelopes > 0) { "mainEventsMaxEnvelopes must be positive" }
        require(mainEventsMaxBytes > 0L) { "mainEventsMaxBytes must be positive" }
        require(errorEventsMaxSlots > 0) { "errorEventsMaxSlots must be positive" }
        require(errorEventsMaxBytes > 0L) { "errorEventsMaxBytes must be positive" }
    }
}

data class PlatformPorts(
    val geometryPort: GeometryPort? = null,
    val textPort: TextPort? = null,
    val resourcePort: ResourcePort? = null,
    val storagePort: StoragePort? = null,
    val pdfPort: PdfPort? = null,
    val renderPort: RenderPort? = null,
    val jobSink: JobSink? = null
)

sealed interface AppCommand {
    data class OpenSession(
        val documentId: DocumentId,
        val createIfMissing: Boolean = true,
        val initialViewport: ViewportState? = null
    ) : AppCommand

    data class CloseSession(
        val session: SessionKey
    ) : AppCommand

    data class SubmitEdit(
        val session: SessionKey,
        val plan: EditPlan,
        val estimatedBytes: Long = 1024L,
        val stagedAssets: List<StagedAssetRef> = emptyList()
    ) : AppCommand

    data class SubmitInputBatch(
        val batch: ImmutableInputBatch
    ) : AppCommand

    data class SubmitControl(
        val session: SessionKey,
        val sequenceNumber: Long,
        val action: InputActionKind,
        val pointerId: Int = 0
    ) : AppCommand

    data class UpdateViewport(
        val state: ViewportState
    ) : AppCommand

    data class AttachSurface(
        val session: SessionKey,
        val surfaceEpoch: SurfaceEpoch,
        val widthPx: Int,
        val heightPx: Int,
        val resizeGeneration: ResizeGeneration
    ) : AppCommand

    data class DetachSurface(
        val session: SessionKey,
        val surfaceEpoch: SurfaceEpoch
    ) : AppCommand

    data class BackgroundFlush(
        val session: SessionKey
    ) : AppCommand

    data class TrimMemory(
        val level: MemoryPressureLevel
    ) : AppCommand

    data class Undo(
        val session: SessionKey
    ) : AppCommand

    data class Redo(
        val session: SessionKey
    ) : AppCommand

    data class DeliverJobReply(
        val session: SessionKey,
        val generation: RequestGeneration,
        val resource: String,
        val payloadBytes: Int = 256
    ) : AppCommand
}

sealed interface AppEvent {
    data class SessionOpened(
        val session: SessionKey,
        val snapshot: DocumentSnapshot,
        val saveStatus: SaveStatus,
        val viewport: ViewportState
    ) : AppEvent

    data class SessionClosed(
        val session: SessionKey,
        val finalDurableRevision: Revision
    ) : AppEvent

    data class SnapshotPublished(
        val session: SessionKey,
        val snapshot: DocumentSnapshot,
        val saveStatus: SaveStatus
    ) : AppEvent

    data class SaveStatusChanged(
        val saveStatus: SaveStatus
    ) : AppEvent

    data class ViewportChanged(
        val viewport: ViewportState
    ) : AppEvent

    data class InputAccepted(
        val session: SessionKey,
        val pointerId: Int,
        val sequenceNumber: Long,
        val sampleCount: Int,
        val action: InputActionKind
    ) : AppEvent

    data class InputDraftCanceled(
        val session: SessionKey,
        val pointerId: Int,
        val sequenceNumber: Long,
        val reason: String
    ) : AppEvent

    data class SurfaceAttachedAck(
        val session: SessionKey,
        val surfaceEpoch: SurfaceEpoch,
        val resizeGeneration: ResizeGeneration
    ) : AppEvent

    data class SurfaceTeardownAck(
        val session: SessionKey,
        val surfaceEpoch: SurfaceEpoch
    ) : AppEvent

    data class JobReplyAccepted(
        val session: SessionKey,
        val generation: RequestGeneration,
        val resource: String
    ) : AppEvent

    data class MemoryTrimmed(
        val level: MemoryPressureLevel,
        val reclaimedBytes: Long
    ) : AppEvent

    data class ErrorEvent(
        val session: SessionKey?,
        val code: String,
        val message: String
    ) : AppEvent

    data class Closed(
        val completedPendingCommits: Int
    ) : AppEvent
}

data class EventBatch(
    val events: List<AppEvent>,
    val errorEvents: List<AppEvent.ErrorEvent>,
    val coalescedMetadataCount: Int = 0
)

interface AppGateway {
    fun trySend(command: AppCommand): Outcome<RequestId, SubmitError>
    fun pollEvents(max: Int): EventBatch
    fun closeAsync(): Outcome<RequestId, SubmitError>
    fun latestSnapshot(session: SessionKey): DocumentSnapshot? = null
    fun latestSaveStatus(session: SessionKey): SaveStatus? = null
    fun latestViewport(session: SessionKey): ViewportState? = null
    fun activeSessionFor(documentId: String): SessionKey? = null
    fun acknowledgeError(error: AppEvent.ErrorEvent) {}
}
