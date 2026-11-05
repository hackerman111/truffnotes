package inkspace.core.ports

import inkspace.core.error.GeometryError
import inkspace.core.error.ResourceError
import inkspace.core.error.SubmitError
import inkspace.core.error.TextError
import inkspace.core.id.AssetId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.outcome.Outcome
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable

@Serializable
data class GeometryRequest(
    val strokeId: ObjectId,
    val origin: DocPoint,
    val samples: List<DocPoint>,
    val baseWidthPt: Double
)

data class PathArtifact(
    val strokeId: ObjectId,
    val bounds: DocRect,
    val segmentCount: Int
)

@Serializable
data class TextLayoutRequest(
    val text: String,
    val fontSizePt: Double,
    val maxWidthPt: Double,
    val isRtl: Boolean = false
)

data class TextLayout(
    val widthPt: Double,
    val heightPt: Double,
    val lineCount: Int
)

@Serializable
data class ResourceRequest(
    val asset: AssetId,
    val byteBudget: Long
)

data class ResourceLease(
    val asset: AssetId,
    val sizeBytes: Long,
    val isAvailable: Boolean
) : AutoCloseable {
    override fun close() {
        // Lease released
    }
}

@Serializable
sealed interface StorageRequest {
    @Serializable
    data class Commit(
        val revision: Revision,
        val payloadBytes: Long
    ) : StorageRequest
}

@Serializable
@JvmInline
value class RequestId(val value: Long)

@Serializable
sealed interface PdfRequest {
    @Serializable
    data class RenderPage(val pageIndex: Int, val width: Int, val height: Int) : PdfRequest
}

@Serializable
sealed interface RenderJob {
    @Serializable
    data class Draw(val bounds: DocRect, val revision: Revision) : RenderJob
    @Serializable
    data class Snapshot(val region: DocRect, val scale: Float) : RenderJob
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
