package inkspace.core.pdf

import inkspace.core.id.AssetId
import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable

@Serializable
data class PdfHandle(val id: Long, val epoch: Long)

@Serializable
data class PdfPageRef(val asset: AssetId, val pageIndex: Int, val cropBox: DocRect, val rotation: Int = 0)

@Serializable
data class PdfTextSpan(val text: String, val bounds: DocRect, val startUtf16: Int)

@Serializable
data class PdfLink(val bounds: DocRect, val targetPage: Int? = null, val uri: String? = null)

@Serializable
data class PdfOutlineEntry(val title: String, val pageIndex: Int, val depth: Int)

sealed interface PdfReply {
    data class Opened(val handle: PdfHandle, val pageCount: Int) : PdfReply
    data class PageInfo(val page: PdfPageRef) : PdfReply
    data class Text(val spans: List<PdfTextSpan>, val truncated: Boolean = false, val nextIndex: Int? = null) : PdfReply
    data class Links(val links: List<PdfLink>) : PdfReply
    data class Outline(val entries: List<PdfOutlineEntry>) : PdfReply
    data class Closed(val handle: PdfHandle) : PdfReply
    data class Failed(val reason: String, val recoverable: Boolean = true) : PdfReply

    class Tile(val handle: PdfHandle, val width: Int, val height: Int, rgba: ByteArray) : PdfReply {
        private val bytes: ByteArray
        init {
            require(width in 1..4096 && height in 1..4096)
            val size = width.toLong() * height * 4L
            require(size <= 8_388_608L && rgba.size.toLong() == size)
            bytes = rgba.copyOf()
        }
        fun copyRgba(): ByteArray = bytes.copyOf()
        val byteSize: Int get() = bytes.size
    }
}
