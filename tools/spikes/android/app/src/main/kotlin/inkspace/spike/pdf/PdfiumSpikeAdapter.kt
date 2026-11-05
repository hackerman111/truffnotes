package inkspace.spike.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.annotation.RequiresApi
import inkspace.spike.model.TextPaintPlan
import inkspace.spike.ports.SpikeTextPort
import io.legere.pdfiumandroid.PdfDocument as PdfiumDoc
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File
import java.io.FileOutputStream

class PdfiumSpikeAdapter(private val context: Context) {

    private val pdfiumCore = PdfiumCore()

    /**
     * Renders a page of a PDF file to a Bitmap.
     */
    fun renderPageToBitmap(pfd: ParcelFileDescriptor, pageIndex: Int, width: Int, height: Int): Bitmap {
        val doc: PdfiumDoc = pdfiumCore.newDocument(pfd)
        try {
            val page: PdfPage = doc.openPage(pageIndex) ?: throw IllegalStateException("Failed to open page $pageIndex")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.renderPageBitmap(bitmap, 0, 0, width, height, false, true, 0, 0)
            page.close()
            return bitmap
        } finally {
            doc.close()
        }
    }

    /**
     * Renders a page of a PDF file directly onto a Surface.
     */
    fun renderPageToSurface(pfd: ParcelFileDescriptor, pageIndex: Int, surface: Surface): Boolean {
        val doc: PdfiumDoc = pdfiumCore.newDocument(pfd)
        try {
            val page: PdfPage = doc.openPage(pageIndex) ?: return false
            val result = page.renderPage(surface, android.graphics.Matrix(), android.graphics.RectF(), false, true, 0, 0)
            page.close()
            return result
        } finally {
            doc.close()
        }
    }

    /**
     * Extracts text from a specific page.
     */
    fun extractPageText(pfd: ParcelFileDescriptor, pageIndex: Int): String {
        val doc: PdfiumDoc = pdfiumCore.newDocument(pfd)
        try {
            val page: PdfPage = doc.openPage(pageIndex) ?: return ""
            val textPage = page.openTextPage()
            val text = textPage.textPageGetText(0, textPage.textPageCountChars()) ?: ""
            textPage.close()
            page.close()
            return text
        } finally {
            doc.close()
        }
    }

    /**
     * Searches text in page.
     */
    fun searchInPage(pfd: ParcelFileDescriptor, pageIndex: Int, query: String): Int {
        val doc: PdfiumDoc = pdfiumCore.newDocument(pfd)
        try {
            val page: PdfPage = doc.openPage(pageIndex) ?: return 0
            val textPage = page.openTextPage()
            val finder = textPage.findStart(query, emptySet(), 0)
            var matches = 0
            if (finder != null) {
                while (finder.findNext()) {
                    matches++
                }
                finder.close()
            }
            textPage.close()
            page.close()
            return matches
        } finally {
            doc.close()
        }
    }

    /**
     * Generates a sample PDF file using Android PdfDocument with vector text in Cyrillic, RTL, CJK, Emoji.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun generateVectorSamplePdf(outputFile: File): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 at 72 dpi
        val page = document.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        // Background
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint().apply {
            color = 0xFFCCCCCC.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
        }
        canvas.drawRect(20.0f, 20.0f, 575.0f, 822.0f, borderPaint)

        // Draw multi-language text using StaticLayout
        val sampleTexts = listOf(
            TextPaintPlan("Truffnotes / Inkspace Android Spike (API 29+)", textSizePx = 22.0f, textColor = 0xFF0D47A1.toInt()),
            TextPaintPlan("Кириллица: Векторные рукописные заметки и экспорт аннотаций", textSizePx = 16.0f),
            TextPaintPlan("RTL: مرحبا بك في محرر الملاحظات / שלום עולם", textSizePx = 16.0f, isRtl = true),
            TextPaintPlan("CJK: 手書きノート編集ソフト / 矢量手写笔记 / 한국어 필기 노트", textSizePx = 16.0f),
            TextPaintPlan("Emoji & Math: 🎨 ✍️ 📐 ∑(x_i) = ∫ f(t) dt 🚀 ✨", textSizePx = 16.0f, containsEmoji = true)
        )

        var yOffset = 50.0f
        for (plan in sampleTexts) {
            canvas.save()
            canvas.translate(40.0f, yOffset)
            val layout = SpikeTextPort.createLayout(plan)
            layout.draw(canvas)
            canvas.restore()
            yOffset += layout.height + 25.0f
        }

        // Draw vector strokes
        val strokePaint = Paint().apply {
            color = 0xFFD32F2F.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(40.0f, yOffset + 10.0f, 555.0f, yOffset + 10.0f, strokePaint)

        document.finishPage(page)

        FileOutputStream(outputFile).use { out ->
            document.writeTo(out)
        }
        document.close()
        return outputFile
    }
}
