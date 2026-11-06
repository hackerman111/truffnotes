package inkspace.pdf

import inkspace.core.error.SubmitError
import inkspace.core.id.AssetId
import inkspace.core.outcome.Outcome
import inkspace.core.pdf.PdfHandle
import inkspace.core.pdf.PdfReply
import inkspace.core.ports.PdfPort
import inkspace.core.ports.PdfRequest
import inkspace.core.ports.RequestId

/** Bounded client protocol; native PDF handles remain in the separately gated binding/service. */
class PdfSession(private val maxQueueDepth: Int = 8) : PdfPort, AutoCloseable {
    data class Work(val id: RequestId, val epoch: Long, val request: PdfRequest)
    private val queued = ArrayDeque<Work>()
    private val inFlight = mutableMapOf<RequestId, Work>()
    private val replies = ArrayDeque<Pair<RequestId, PdfReply>>()
    private val pages = mutableMapOf<PdfHandle, Int>()
    private val assets = mutableMapOf<PdfHandle, AssetId>()
    private var nextId = 0L
    private var closed = false
    private var owner: Long? = null
    @Volatile var epoch: Long = 1L
        private set

    init { require(maxQueueDepth in 1..128) }

    @Synchronized
    override fun trySubmit(request: PdfRequest): Outcome<RequestId, SubmitError> {
        if (closed) return Outcome.Failure(SubmitError.ShutdownInProgress("PDF session closed"))
        val depth = queued.size + inFlight.size + replies.size
        if (depth >= maxQueueDepth) return Outcome.Failure(SubmitError.QueueOverflow(depth, maxQueueDepth))
        val reason = invalidRequest(request)
        if (reason != null) return Outcome.Failure(SubmitError.InvalidPayload(reason))
        val id = RequestId(++nextId)
        queued.addLast(Work(id, epoch, request))
        return Outcome.Success(id)
    }

    @Synchronized
    fun take(): Work? {
        checkOwner()
        val work = queued.removeFirstOrNull() ?: return null
        inFlight[work.id] = work
        return work
    }

    @Synchronized
    fun complete(work: Work, reply: PdfReply): Outcome<Unit, SubmitError> {
        checkOwner()
        val expected = inFlight[work.id]
        if (work.epoch != epoch || expected != work) return Outcome.Failure(SubmitError.StaleSession("pdf", "Old or unknown request"))
        if (!validReply(work.request, reply)) return Outcome.Failure(SubmitError.InvalidPayload("Invalid or oversized PDF reply"))
        inFlight.remove(work.id)
        when (reply) {
            is PdfReply.Opened -> {
                pages[reply.handle] = reply.pageCount
                assets[reply.handle] = (work.request as PdfRequest.Open).asset
            }
            is PdfReply.Closed -> { pages.remove(reply.handle); assets.remove(reply.handle) }
            else -> Unit
        }
        replies.addLast(work.id to reply)
        return Outcome.Success(Unit)
    }

    @Synchronized fun poll(): Pair<RequestId, PdfReply>? = replies.removeFirstOrNull()
    @Synchronized fun discardReply(id: RequestId) { replies.removeAll { it.first == id } }
    @Synchronized fun isOutstanding(id: RequestId): Boolean = queued.any { it.id == id } || id in inFlight || replies.any { it.first == id }
    @Synchronized fun outstandingIds(): List<RequestId> = queued.map { it.id } + inFlight.keys
    @Synchronized fun serviceDied() {
        epoch++
        queued.clear()
        inFlight.clear()
        replies.clear()
        pages.clear()
        assets.clear()
        owner = null
    }
    @Synchronized override fun close() { serviceDied(); closed = true }

    private fun checkOwner() {
        val current = Thread.currentThread().id
        check(owner == null || owner == current) { "PDF binding accessed from another thread" }
        owner = current
    }

    private fun invalidRequest(request: PdfRequest): String? {
        val handle = when (request) {
            is PdfRequest.Open -> return if (pages.size >= 128 || (request.password?.length ?: 0) > 1024) "Open budget exceeded" else null
            is PdfRequest.RenderPage -> return "Legacy render requires explicit asset handle"
            is PdfRequest.Info -> request.handle
            is PdfRequest.Tile -> request.handle
            is PdfRequest.Text -> request.handle
            is PdfRequest.Search -> request.handle
            is PdfRequest.Links -> request.handle
            is PdfRequest.Outline -> request.handle
            is PdfRequest.Close -> request.handle
        }
        if (handle.epoch != epoch || handle !in pages) return "Stale or unknown handle"
        val pageIndex = when (request) {
            is PdfRequest.Info -> request.pageIndex
            is PdfRequest.Tile -> request.pageIndex
            is PdfRequest.Text -> request.pageIndex
            is PdfRequest.Search -> request.pageIndex
            is PdfRequest.Links -> request.pageIndex
            else -> null
        }
        if (pageIndex != null && pageIndex !in 0 until pages.getValue(handle)) return "Page index out of range"
        return when (request) {
            is PdfRequest.Tile -> try { tileByteSize(request.width, request.height); null } catch (_: IllegalArgumentException) { "Tile budget exceeded" }
            is PdfRequest.Text -> if (request.maxChars !in 1..65536 || request.startIndex !in 0..2_000_000) "Text budget exceeded" else null
            is PdfRequest.Search -> if (request.query.isEmpty() || request.query.length > 1024 || request.maxMatches !in 1..256) "Search budget exceeded" else null
            is PdfRequest.Outline -> if (request.maxEntries !in 1..4096) "Outline budget exceeded" else null
            else -> null
        }
    }

    private fun validReply(request: PdfRequest, reply: PdfReply): Boolean = when (reply) {
        is PdfReply.Failed -> reply.reason.length <= 4096
        is PdfReply.Opened -> request is PdfRequest.Open && reply.handle.epoch == epoch && reply.handle !in pages && reply.pageCount in 1..100000 && pages.size < 128
        is PdfReply.Closed -> request is PdfRequest.Close && reply.handle == request.handle
        is PdfReply.Tile -> request is PdfRequest.Tile && reply.handle == request.handle && reply.width == request.width && reply.height == request.height
        is PdfReply.Text -> when (request) {
            is PdfRequest.Text -> reply.spans.size <= request.maxChars && reply.spans.sumOf { it.text.length.toLong() } <= request.maxChars && reply.spans.all { it.startUtf16 >= 0 }
            is PdfRequest.Search -> reply.spans.size <= request.maxMatches && reply.spans.sumOf { it.text.length.toLong() } <= 65536L && reply.spans.all { it.startUtf16 >= 0 }
            else -> false
        }
        is PdfReply.Links -> request is PdfRequest.Links && reply.links.size <= 4096 && reply.links.sumOf { (it.uri?.length ?: 0).toLong() } <= 65536L && reply.links.all { (it.uri?.length ?: 0) <= 4096 }
        is PdfReply.Outline -> request is PdfRequest.Outline && reply.entries.size <= request.maxEntries && reply.entries.sumOf { it.title.length.toLong() } <= 65536L && reply.entries.all { it.depth in 0..31 && it.title.length <= 4096 }
        is PdfReply.PageInfo -> request is PdfRequest.Info && reply.page.asset == assets[request.handle] && reply.page.pageIndex == request.pageIndex && runCatching { PdfPageTransform(reply.page.cropBox, reply.page.rotation) }.isSuccess
    }
}
