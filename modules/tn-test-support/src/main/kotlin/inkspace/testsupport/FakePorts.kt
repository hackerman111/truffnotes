package inkspace.testsupport

import inkspace.core.error.GeometryError
import inkspace.core.error.ResourceError
import inkspace.core.error.SubmitError
import inkspace.core.error.TextError
import inkspace.core.outcome.Outcome
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.GeometryRequest
import inkspace.core.ports.JobId
import inkspace.core.ports.JobSink
import inkspace.core.ports.JobSpec
import inkspace.core.ports.PathArtifact
import inkspace.core.ports.PdfPort
import inkspace.core.ports.PdfRequest
import inkspace.core.ports.RenderJob
import inkspace.core.ports.RenderPort
import inkspace.core.ports.RequestId
import inkspace.core.ports.ResourceLease
import inkspace.core.ports.ResourcePort
import inkspace.core.ports.ResourceRequest
import inkspace.core.ports.StoragePort
import inkspace.core.ports.StorageRequest
import inkspace.core.ports.TextLayout
import inkspace.core.ports.TextLayoutRequest
import inkspace.core.ports.TextPort
import inkspace.core.unit.DocRect
import java.util.concurrent.atomic.AtomicLong

class FakeGeometryPort : GeometryPort {
    override fun paths(request: GeometryRequest): Outcome<PathArtifact, GeometryError> {
        if (request.samples.isEmpty()) {
            return Outcome.Failure(GeometryError.ComputationFailed("Empty sample list"))
        }
        val minX = request.samples.minOf { it.x } + request.origin.x
        val maxX = request.samples.maxOf { it.x } + request.origin.x
        val minY = request.samples.minOf { it.y } + request.origin.y
        val maxY = request.samples.maxOf { it.y } + request.origin.y
        return Outcome.Success(
            PathArtifact(
                strokeId = request.strokeId,
                bounds = DocRect(minX, minY, maxX, maxY),
                segmentCount = request.samples.size
            )
        )
    }
}

class FakeTextPort : TextPort {
    override fun layout(request: TextLayoutRequest): Outcome<TextLayout, TextError> {
        val approxWidth = (request.text.length * request.fontSizePt * 0.6).coerceAtMost(request.maxWidthPt)
        val lines = ((request.text.length * request.fontSizePt * 0.6) / request.maxWidthPt).toInt().coerceAtLeast(1)
        val height = lines * request.fontSizePt * 1.2
        return Outcome.Success(TextLayout(widthPt = approxWidth, heightPt = height, lineCount = lines))
    }
}

class FakeResourcePort(private val maxBudgetBytes: Long = 64 * 1024 * 1024L) : ResourcePort {
    override fun read(request: ResourceRequest): Outcome<ResourceLease, ResourceError> {
        if (request.byteBudget > maxBudgetBytes) {
            return Outcome.Failure(ResourceError.ResourceExhausted("Requested ${request.byteBudget} > $maxBudgetBytes"))
        }
        return Outcome.Success(
            ResourceLease(
                asset = request.asset,
                sizeBytes = request.byteBudget,
                isAvailable = true
            )
        )
    }
}

class BoundedFakeStoragePort(private val maxQueueDepth: Int = 64) : StoragePort {
    private val seq = AtomicLong(1L)
    private val queue = ArrayDeque<StorageRequest>()

    @Synchronized
    override fun trySubmit(request: StorageRequest): Outcome<RequestId, SubmitError> {
        if (queue.size >= maxQueueDepth) {
            return Outcome.Failure(SubmitError.QueueOverflow(queue.size, maxQueueDepth))
        }
        queue.addLast(request)
        return Outcome.Success(RequestId(seq.getAndIncrement()))
    }

    @Synchronized
    fun pollNext(): StorageRequest? = if (queue.isEmpty()) null else queue.removeFirst()

    @Synchronized
    fun pendingCount(): Int = queue.size
}

class BoundedFakePdfPort(private val maxQueueDepth: Int = 32) : PdfPort {
    private val seq = AtomicLong(1L)
    private val queue = ArrayDeque<PdfRequest>()

    @Synchronized
    override fun trySubmit(request: PdfRequest): Outcome<RequestId, SubmitError> {
        if (queue.size >= maxQueueDepth) {
            return Outcome.Failure(SubmitError.QueueOverflow(queue.size, maxQueueDepth))
        }
        queue.addLast(request)
        return Outcome.Success(RequestId(seq.getAndIncrement()))
    }

    @Synchronized
    fun pendingCount(): Int = queue.size
}

class BoundedFakeRenderPort(private val maxQueueDepth: Int = 16) : RenderPort {
    private val seq = AtomicLong(1L)
    private val queue = ArrayDeque<RenderJob>()

    @Synchronized
    override fun trySubmit(job: RenderJob): Outcome<RequestId, SubmitError> {
        if (queue.size >= maxQueueDepth) {
            return Outcome.Failure(SubmitError.QueueOverflow(queue.size, maxQueueDepth))
        }
        queue.addLast(job)
        return Outcome.Success(RequestId(seq.getAndIncrement()))
    }

    @Synchronized
    fun pendingCount(): Int = queue.size
}

class BoundedFakeJobSink(private val maxQueueDepth: Int = 64) : JobSink {
    private val queue = ArrayDeque<JobSpec>()

    @Synchronized
    override fun trySubmit(job: JobSpec): Outcome<JobId, SubmitError> {
        if (queue.size >= maxQueueDepth) {
            return Outcome.Failure(SubmitError.QueueOverflow(queue.size, maxQueueDepth))
        }
        queue.addLast(job)
        return Outcome.Success(JobId(job.jobId))
    }

    @Synchronized
    fun pendingCount(): Int = queue.size
}
