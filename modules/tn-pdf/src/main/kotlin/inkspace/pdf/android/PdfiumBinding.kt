package inkspace.pdf.android

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import inkspace.core.id.AssetId
import inkspace.core.pdf.*
import inkspace.core.ports.PdfRequest
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.pdf.PdfPageTransform
import inkspace.pdf.resolvePdfPageBox
import inkspace.pdf.tileByteSize
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.Bookmark
import java.nio.ByteBuffer

/** Constructed exclusively inside the isolated service's single worker thread. */
internal class PdfiumBinding : AutoCloseable {
    private data class Document(val pdf: PdfDocument, val fd: ParcelFileDescriptor, val asset: AssetId)
    private val owner = Thread.currentThread().id
    private val core = PdfiumCore()
    private val documents = mutableMapOf<PdfHandle, Document>()
    private var serial = 0L

    fun execute(request: PdfRequest, descriptor: ParcelFileDescriptor?, epoch: Long): PdfReply {
        check(Thread.currentThread().id == owner)
        try {
            if (request is PdfRequest.Open) {
                require(documents.size < 16 && epoch > 0L)
                require((request.password?.length ?: 0) <= 1024)
                val fd = requireNotNull(descriptor)
                require(fd.statSize in 1..268_435_456L) { "PDF source must be staged within 256 MiB" }
                val document = core.newDocument(fd, request.password)
                try {
                    val count = document.getPageCount()
                    require(count in 1..100000)
                    val handle = PdfHandle(++serial, epoch)
                    documents[handle] = Document(document, fd, request.asset)
                    return PdfReply.Opened(handle, count)
                } catch (failure: Exception) { document.close(); throw failure }
            }
            descriptor?.close()
            val handle = when (request) {
                is PdfRequest.Info -> request.handle
                is PdfRequest.Tile -> request.handle
                is PdfRequest.Text -> request.handle
                is PdfRequest.Search -> request.handle
                is PdfRequest.Links -> request.handle
                is PdfRequest.Outline -> request.handle
                is PdfRequest.Close -> request.handle
                else -> error("Explicit PDF handle required")
            }
            require(handle.epoch == epoch)
            val document = requireNotNull(documents[handle]) { "Unknown PDF handle" }
            return when (request) {
                is PdfRequest.Close -> {
                    documents.remove(handle)
                    try { document.pdf.close() } finally { document.fd.close() }
                    PdfReply.Closed(handle)
                }
                is PdfRequest.Info -> page(document, request.pageIndex) { page -> PdfReply.PageInfo(reference(document.asset, page)) }
                is PdfRequest.Tile -> page(document, request.pageIndex) { page -> render(page, handle, request) }
                is PdfRequest.Text -> page(document, request.pageIndex) { page ->
                    page.openTextPage().use { text -> text(page, text, request) }
                }
                is PdfRequest.Search -> page(document, request.pageIndex) { page ->
                    page.openTextPage().use { text -> search(page, text, request) }
                }
                is PdfRequest.Links -> page(document, request.pageIndex) { page ->
                    val transform = transform(page)
                    var uriBytes = 0
                    val nativeLinks = page.getPageLinks()
                    require(nativeLinks.size <= 1024) { "PDF link count exceeds reply cap" }
                    val links = nativeLinks.map { link ->
                        val uri = link.uri
                        require((uri?.length ?: 0) <= 2048)
                        uriBytes += uri?.length ?: 0
                        require(uriBytes <= 32768)
                        PdfLink(mappedBox(link.bounds, transform), link.destPageIdx?.takeIf { it in 0 until document.pdf.getPageCount() }, uri)
                    }
                    PdfReply.Links(links)
                }
                is PdfRequest.Outline -> {
                    require(request.maxEntries in 1..4096)
                    val entries = mutableListOf<PdfOutlineEntry>()
                    var chars = 0
                    fun append(bookmarks: List<Bookmark>, depth: Int) {
                        if (depth > 31) return
                        for (bookmark in bookmarks) {
                            if (entries.size >= request.maxEntries || chars >= 32768) return
                            val title = (bookmark.title ?: "").take(minOf(1024, 32768 - chars))
                            chars += title.length
                            if (bookmark.pageIdx in 0 until document.pdf.getPageCount().toLong()) entries += PdfOutlineEntry(title, bookmark.pageIdx.toInt(), depth)
                            append(bookmark.children, depth + 1)
                        }
                    }
                    append(document.pdf.getTableOfContents(), 0)
                    PdfReply.Outline(entries)
                }
                else -> error("Unsupported PDF request")
            }
        } catch (failure: Exception) {
            if (request is PdfRequest.Open) descriptor?.close()
            return PdfReply.Failed((failure.javaClass.simpleName + ": " + (failure.message ?: "PDF operation failed")).take(1024))
        }
    }

    private fun <T> page(document: Document, index: Int, block: (PdfPage) -> T): T {
        require(index in 0 until document.pdf.getPageCount())
        return requireNotNull(document.pdf.openPage(index)).use { page -> transform(page); block(page) }
    }

    private fun reference(asset: AssetId, page: PdfPage): PdfPageRef {
        val mapping = transform(page)
        return PdfPageRef(asset, page.pageIndex, mapping.cropBox, mapping.rotation)
    }

    private fun transform(page: PdfPage): PdfPageTransform {
        fun normalized(rect: RectF) = DocRect(minOf(rect.left, rect.right).toDouble(), minOf(rect.top, rect.bottom).toDouble(), maxOf(rect.left, rect.right).toDouble(), maxOf(rect.top, rect.bottom).toDouble())
        val box = resolvePdfPageBox(normalized(page.getPageCropBox()), normalized(page.getPageMediaBox()))
        val rotation = page.getPageRotation()
        return PdfPageTransform(box, if (rotation in 0..3) rotation * 90 else rotation)
    }

    private fun mappedBox(box: RectF, transform: PdfPageTransform): DocRect {
        val a = transform.pdfToDoc(DocPoint(box.left.toDouble(), box.top.toDouble()))
        val b = transform.pdfToDoc(DocPoint(box.right.toDouble(), box.bottom.toDouble()))
        return DocRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    private fun render(page: PdfPage, handle: PdfHandle, request: PdfRequest.Tile): PdfReply {
        val bytes = tileByteSize(request.width, request.height)
        require(request.region.width > 0 && request.region.height > 0)
        val mapping = transform(page)
        val scaleX = request.width / request.region.width
        val scaleY = request.height / request.region.height
        require(scaleX.isFinite() && scaleY.isFinite() && scaleX <= 10000 && scaleY <= 10000)
        fun map(point: DocPoint): DocPoint {
            val doc = mapping.pdfToDoc(point)
            return DocPoint((doc.x - request.region.left) * scaleX, (doc.y - request.region.top) * scaleY)
        }
        val zero = map(DocPoint(0.0, 0.0))
        val x = map(DocPoint(1.0, 0.0))
        val y = map(DocPoint(0.0, 1.0))
        val matrix = Matrix().apply { setValues(floatArrayOf((x.x-zero.x).toFloat(), (y.x-zero.x).toFloat(), zero.x.toFloat(), (x.y-zero.y).toFloat(), (y.y-zero.y).toFloat(), zero.y.toFloat(), 0f, 0f, 1f)) }
        val bitmap = Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.renderPageBitmap(bitmap, matrix, RectF(0f, 0f, request.width.toFloat(), request.height.toFloat()), true, true)
            val buffer = ByteBuffer.allocate(bytes.toInt())
            bitmap.copyPixelsToBuffer(buffer)
            PdfReply.Tile(handle, request.width, request.height, buffer.array())
        } finally { bitmap.recycle() }
    }

    private fun text(page: PdfPage, text: PdfTextPage, request: PdfRequest.Text): PdfReply.Text {
        require(request.maxChars in 1..65536)
        val total = text.textPageCountChars()
        require(total in 0..2_000_000)
        val start = request.startIndex
        require(start in 0..total)
        val end = minOf(total, start + minOf(request.maxChars, 256))
        var utf16 = 0
        var previous = 0
        while (previous < start) {
            val count = minOf(4096, start - previous)
            utf16 += (text.textPageGetText(previous, count) ?: "").length
            previous += count
        }
        val transform = transform(page)
        val spans = mutableListOf<PdfTextSpan>()
        var next = start
        var units = 0
        for (index in start until end) {
            val value = text.textPageGetText(index, 1) ?: ""
            if (units + value.length > request.maxChars) break
            val box = text.textPageGetCharBox(index)
            if (value.isNotEmpty()) spans += PdfTextSpan(value, if (box == null) DocRect(0.0, 0.0, 0.0, 0.0) else mappedBox(box, transform), utf16)
            utf16 += value.length
            units += value.length
            next = index + 1
        }
        require(next > start || start == total) { "Text budget cannot hold a complete Unicode character" }
        return PdfReply.Text(spans, next < total, next)
    }

    private fun search(page: PdfPage, text: PdfTextPage, request: PdfRequest.Search): PdfReply.Text {
        require(request.query.length in 1..1024 && request.maxMatches in 1..256)
        val spans = mutableListOf<PdfTextSpan>()
        val mapping = transform(page)
        var truncated = false
        var units = 0
        text.findStart(request.query, emptySet(), 0)?.use { finder ->
            while (spans.size < request.maxMatches && finder.findNext()) {
                val start = finder.getSchResultIndex()
                val count = finder.getSchCount()
                require(start in 0..2_000_000 && count in 1..65536)
                require(start <= text.textPageCountChars() && count <= text.textPageCountChars() - start)
                val value = text.textPageGetText(start, count) ?: ""
                require(value.length <= 1024)
                if (units + value.length > 65536) { truncated = true; break }
                var bounds: DocRect? = null
                for (index in start until start + count) {
                    text.textPageGetCharBox(index)?.let { rect -> bounds = bounds?.union(mappedBox(rect, mapping)) ?: mappedBox(rect, mapping) }
                }
                var utf16 = 0
                var cursor = 0
                while (cursor < start) {
                    val chunk = minOf(4096, start - cursor)
                    utf16 += (text.textPageGetText(cursor, chunk) ?: "").length
                    cursor += chunk
                }
                bounds?.let { spans += PdfTextSpan(value, it, utf16); units += value.length }
            }
            if (spans.size == request.maxMatches && finder.findNext()) truncated = true
        }
        return PdfReply.Text(spans, truncated)
    }

    override fun close() {
        check(Thread.currentThread().id == owner)
        for (document in documents.values) runCatching { try { document.pdf.close() } finally { document.fd.close() } }
        documents.clear()
    }
}
