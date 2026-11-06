package inkspace.core.ports

import inkspace.core.codec.WriteSet
import inkspace.core.error.GeometryError
import inkspace.core.error.ResourceError
import inkspace.core.error.SubmitError
import inkspace.core.error.TextError
import inkspace.core.id.AssetId
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.TransactionId
import inkspace.core.id.VersionId
import inkspace.core.model.SceneObject
import inkspace.core.outcome.Outcome
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable

@Serializable
data class GeometryRequest(
    val strokeId: ObjectId,
    val origin: DocPoint,
    val samples: List<DocPoint>,
    val baseWidthPt: Double,
    val brushId: String = "ball",
    val smoothingVersion: Int = 1,
    val scaleBucket: Int = 0
)

data class PathArtifact(
    val strokeId: ObjectId,
    val bounds: DocRect,
    val segmentCount: Int,
    val origin: DocPoint = DocPoint(bounds.left, bounds.top),
    val relativePoints: List<DocPoint> = emptyList(),
    val estimatedBytes: Long = 64L + segmentCount.toLong() * 16L
) {
    init {
        require(segmentCount >= 0) { "segmentCount must be non-negative" }
    }
}

@Serializable
data class TextLayoutRequest(
    val text: String,
    val fontSizePt: Double,
    val maxWidthPt: Double,
    val isRtl: Boolean = false,
    val payload: inkspace.core.payload.TextPayload? = null
)

data class TextLayout(
    val widthPt: Double,
    val heightPt: Double,
    val lineCount: Int,
    val lines: List<String> = emptyList(),
    val estimatedBytes: Long = 128L + lines.sumOf { it.length.toLong() * 2L },
    val paintPlan: inkspace.core.text.TextPaintArtifact? = null
)

@Serializable
data class ResourceRequest(
    val asset: AssetId,
    val byteBudget: Long,
    val targetWidthPx: Int = 512,
    val targetHeightPx: Int = 512,
    val posterFrame: Int = 0
)

data class ResourceLease(
    val asset: AssetId,
    val sizeBytes: Long,
    val isAvailable: Boolean,
    val imageTile: inkspace.core.render.TileArtifact? = null
) : AutoCloseable {
    private var releaseAction: (() -> Unit)? = null
    private var closed: Boolean = false

    constructor(
        asset: AssetId,
        sizeBytes: Long,
        isAvailable: Boolean,
        onRelease: () -> Unit
    ) : this(asset, sizeBytes, isAvailable) {
        this.releaseAction = onRelease
    }

    constructor(asset: AssetId, sizeBytes: Long, isAvailable: Boolean,
        imageTile: inkspace.core.render.TileArtifact?, onRelease: () -> Unit) : this(asset,sizeBytes,isAvailable,imageTile) {
        this.releaseAction=onRelease
    }

    fun isClosed(): Boolean = closed

    override fun close() {
        if (!closed) {
            closed = true
            releaseAction?.invoke()
        }
    }
}

@Serializable
data class StagedAssetRef(
    val assetId: AssetId,
    val stagingId: String,
    val sizeBytes: Long,
    val sha256Hex: String
)

@Serializable
data class DurableAck(
    val document: DocumentId,
    val revision: Revision,
    val transactionId: TransactionId,
    val wasDeduplicated: Boolean = false
)

@Serializable
sealed interface StorageRequest {
    @Serializable
    data class Open(
        val documentId: DocumentId,
        val createIfMissing: Boolean = true
    ) : StorageRequest

    @Serializable
    data class Read(
        val documentId: DocumentId,
        val spaceId: SpaceId? = null,
        val pageId: PageId? = null,
        val objectIds: List<ObjectId> = emptyList(),
        val byteBudget: Long = 8_388_608L
    ) : StorageRequest

    @Serializable
    data class StageDraftChunk(
        val documentId: DocumentId,
        val gestureId: GestureId,
        val chunkIndex: Int,
        val encodedBytes: ByteArray
    ) : StorageRequest

    @Serializable
    data class Commit(
        val revision: Revision,
        val payloadBytes: Long = 0L,
        val transactionId: TransactionId = TransactionId("tx-${revision.value}"),
        val expectedDurableBase: Revision = Revision((revision.value - 1L).coerceAtLeast(0L)),
        val writeSet: WriteSet? = null,
        val stagedAssets: List<StagedAssetRef> = emptyList()
    ) : StorageRequest

    @Serializable
    data class Flush(
        val documentId: DocumentId
    ) : StorageRequest

    @Serializable
    data class Close(
        val documentId: DocumentId
    ) : StorageRequest

    @Serializable
    data class Backup(
        val documentId: DocumentId,
        val targetPath: String
    ) : StorageRequest

    @Serializable
    data class Version(
        val documentId: DocumentId,
        val versionId: VersionId,
        val label: String,
        val isPinned: Boolean = false
    ) : StorageRequest

    @Serializable
    data class Collect(
        val documentId: DocumentId,
        val pinnedAssets: Set<AssetId> = emptySet(),
        val cancelGestureIds: Set<GestureId> = emptySet()
    ) : StorageRequest
}

sealed interface StorageReply {
    data class Opened(
        val documentId: DocumentId,
        val durableRevision: Revision,
        val totalObjects: Int
    ) : StorageReply

    data class ReadResult(
        val documentId: DocumentId,
        val revision: Revision,
        val objects: List<SceneObject>,
        val lease: ResourceLease
    ) : StorageReply

    data class DraftChunkStaged(
        val documentId: DocumentId,
        val gestureId: GestureId,
        val chunkIndex: Int,
        val stagedBytes: Long
    ) : StorageReply

    data class Committed(
        val ack: DurableAck
    ) : StorageReply

    data class Flushed(
        val documentId: DocumentId,
        val durableRevision: Revision
    ) : StorageReply

    data class Closed(
        val documentId: DocumentId
    ) : StorageReply

    data class BackedUp(
        val documentId: DocumentId,
        val durableRevision: Revision,
        val targetPath: String,
        val sizeBytes: Long
    ) : StorageReply

    data class VersionCreated(
        val documentId: DocumentId,
        val versionId: VersionId,
        val revision: Revision
    ) : StorageReply

    data class Collected(
        val documentId: DocumentId,
        val deletedOrphanAssets: Int,
        val deletedDraftChunks: Int,
        val reclaimedBytes: Long
    ) : StorageReply
}

@Serializable
@JvmInline
value class RequestId(val value: Long)

@Serializable
sealed interface PdfRequest {
    @Serializable
    data class Open(val asset: AssetId, val password: String? = null) : PdfRequest
    @Serializable
    data class Info(val handle: inkspace.core.pdf.PdfHandle, val pageIndex: Int) : PdfRequest
    @Serializable
    data class Tile(val handle: inkspace.core.pdf.PdfHandle, val pageIndex: Int, val width: Int, val height: Int, val region: DocRect) : PdfRequest
    @Serializable
    data class Text(val handle: inkspace.core.pdf.PdfHandle, val pageIndex: Int, val maxChars: Int = 65536, val startIndex: Int = 0) : PdfRequest
    @Serializable
    data class Search(val handle: inkspace.core.pdf.PdfHandle, val pageIndex: Int, val query: String, val maxMatches: Int = 256) : PdfRequest
    @Serializable
    data class Links(val handle: inkspace.core.pdf.PdfHandle, val pageIndex: Int) : PdfRequest
    @Serializable
    data class Outline(val handle: inkspace.core.pdf.PdfHandle, val maxEntries: Int = 4096) : PdfRequest
    @Serializable
    data class Close(val handle: inkspace.core.pdf.PdfHandle) : PdfRequest
    @Serializable
    data class RenderPage(val pageIndex: Int, val width: Int, val height: Int) : PdfRequest
}

@Serializable
sealed interface RenderJob {
    @Serializable
    data class Draw(val bounds: DocRect, val revision: Revision) : RenderJob

    @Serializable
    data class Snapshot(val region: DocRect, val scale: Float) : RenderJob

    @Serializable
    data class PdfOverlay(val pageId: PageId, val pageBounds: DocRect, val revision: Revision) : RenderJob
}

@Serializable
data class JobSpec(
    val jobId: String,
    val priority: Int
)

@Serializable
@JvmInline
value class JobId(val value: String)

interface GeometryPort {
    fun paths(request: GeometryRequest): Outcome<PathArtifact, GeometryError>
}

interface TextPort {
    fun layout(request: TextLayoutRequest): Outcome<TextLayout, TextError>
}

interface ResourcePort {
    fun read(request: ResourceRequest): Outcome<ResourceLease, ResourceError>
}

interface StoragePort {
    fun trySubmit(request: StorageRequest): Outcome<RequestId, SubmitError>
}

interface PdfPort {
    fun trySubmit(request: PdfRequest): Outcome<RequestId, SubmitError>
}

interface RenderPort {
    fun trySubmit(job: RenderJob): Outcome<RequestId, SubmitError>
}

interface JobSink {
    fun trySubmit(job: JobSpec): Outcome<JobId, SubmitError>
}
