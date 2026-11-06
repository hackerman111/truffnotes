package inkspace.pdf.android

import android.os.Bundle
import android.os.SharedMemory
import android.system.OsConstants
import inkspace.core.pdf.*
import inkspace.pdf.tileByteSize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val PDF_REQUEST = 1
internal const val PDF_REPLY = 2
internal const val PDF_CANCEL = 3
internal const val MAX_WIRE_CHARS = 262144
internal val pdfJson = Json { ignoreUnknownKeys = false; explicitNulls = false }

@Serializable
internal data class PdfWire(
    val kind: String,
    val handle: PdfHandle? = null,
    val count: Int = 0,
    val page: PdfPageRef? = null,
    val spans: List<PdfTextSpan> = emptyList(),
    val links: List<PdfLink> = emptyList(),
    val outline: List<PdfOutlineEntry> = emptyList(),
    val truncated: Boolean = false,
    val nextIndex: Int? = null,
    val width: Int = 0,
    val height: Int = 0,
    val error: String = ""
)

internal fun encodeReply(reply: PdfReply): Bundle {
    val wire = when (reply) {
        is PdfReply.Opened -> PdfWire("open", reply.handle, count = reply.pageCount)
        is PdfReply.PageInfo -> PdfWire("info", page = reply.page)
        is PdfReply.Tile -> PdfWire("tile", reply.handle, width = reply.width, height = reply.height)
        is PdfReply.Text -> PdfWire("text", spans = reply.spans, truncated = reply.truncated, nextIndex = reply.nextIndex)
        is PdfReply.Links -> PdfWire("links", links = reply.links)
        is PdfReply.Outline -> PdfWire("outline", outline = reply.entries)
        is PdfReply.Closed -> PdfWire("close", reply.handle)
        is PdfReply.Failed -> PdfWire("error", error = reply.reason.take(1024))
    }
    val json = pdfJson.encodeToString(PdfWire.serializer(), wire)
    require(json.length <= MAX_WIRE_CHARS) { "Reply exceeds IPC metadata cap" }
    return Bundle().apply {
        putString("reply", json)
        if (reply is PdfReply.Tile) {
            val memory = SharedMemory.create("pdf-tile", reply.byteSize)
            try {
                val map = memory.mapReadWrite()
                try { map.put(reply.copyRgba()) } finally { SharedMemory.unmap(map) }
                require(memory.setProtect(OsConstants.PROT_READ))
                putParcelable("tile", memory)
            } catch (failure: Throwable) { memory.close(); throw failure }
        }
    }
}

internal fun decodeReply(bundle: Bundle): PdfReply {
    bundle.classLoader = SharedMemory::class.java.classLoader
    @Suppress("DEPRECATION") val memory = bundle.getParcelable<SharedMemory>("tile")
    try {
        val encoded = requireNotNull(bundle.getString("reply"))
        require(encoded.length <= MAX_WIRE_CHARS)
        val wire = pdfJson.decodeFromString(PdfWire.serializer(), encoded)
        return when (wire.kind) {
            "open" -> { require(wire.count in 1..100000); PdfReply.Opened(requireNotNull(wire.handle), wire.count) }
            "info" -> PdfReply.PageInfo(requireNotNull(wire.page))
            "close" -> PdfReply.Closed(requireNotNull(wire.handle))
            "error" -> PdfReply.Failed(wire.error.take(1024))
            "text" -> { require(wire.spans.size <= 4096 && wire.spans.sumOf { it.text.length.toLong() } <= 65536); PdfReply.Text(wire.spans, wire.truncated, wire.nextIndex) }
            "links" -> { require(wire.links.size <= 4096); PdfReply.Links(wire.links) }
            "outline" -> { require(wire.outline.size <= 4096); PdfReply.Outline(wire.outline) }
            "tile" -> {
                val bytes = tileByteSize(wire.width, wire.height).toInt()
                val shared = requireNotNull(memory)
                require(shared.size == bytes)
                val mapped = shared.mapReadOnly()
                val pixels = ByteArray(bytes)
                try { mapped.get(pixels) } finally { SharedMemory.unmap(mapped) }
                PdfReply.Tile(requireNotNull(wire.handle), wire.width, wire.height, pixels)
            }
            else -> error("Unknown PDF reply")
        }
    } finally { memory?.close() }
}

internal fun closeReplyBuffer(bundle: Bundle) {
    bundle.classLoader = SharedMemory::class.java.classLoader
    @Suppress("DEPRECATION") val memory = bundle.getParcelable<SharedMemory>("tile")
    memory?.close()
}
