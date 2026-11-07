package inkspace.pdf.android

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Each callback must replay the existing RenderRequest.PdfOverlay onto this page's Canvas.
 * PdfCompositionWriter imports the closed page document as an appearance form. */
class AndroidPdfWriter : AutoCloseable {
    private val document = PdfDocument()
    private var pages = 0
    private var closed = false
    private var written = false

    fun page(widthPt: Int, heightPt: Int, replay: (Canvas) -> Unit) {
        check(!closed && !written)
        require(widthPt in 1..14400 && heightPt in 1..14400)
        val page = document.startPage(PdfDocument.PageInfo.Builder(widthPt, heightPt, pages + 1).create())
        try { replay(page.canvas) } finally { document.finishPage(page) }
        pages++
    }
    fun write(output: OutputStream) {
        check(!closed && !written && pages > 0)
        document.writeTo(output)
        written = true
    }
    override fun close() { if (!closed) { closed = true; document.close() } }
}
