package inkspace.spike

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkspace.spike.pdf.PdfiumSpikeAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpikeSurfacePdfInstrumentedTest {

    @Test
    fun testPdfiumAndVectorPdfPipeline() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = PdfiumSpikeAdapter(appContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pdfFile = File(appContext.cacheDir, "instrumented_vector_test.pdf")
            adapter.generateVectorSamplePdf(pdfFile)

            assertTrue("Generated PDF must exist and have non-zero size", pdfFile.exists() && pdfFile.length() > 0)

            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            assertNotNull(pfd)

            try {
                // 1. Render to Bitmap via PDFium
                val bitmap = adapter.renderPageToBitmap(pfd, 0, 595, 842)
                assertNotNull(bitmap)
                assertEquals(595, bitmap.width)
                assertEquals(842, bitmap.height)

                // 2. Extract text via PDFium
                val extractedText = adapter.extractPageText(pfd, 0)
                assertNotNull(extractedText)

                // 3. Search via PDFium
                val matches = adapter.searchInPage(pfd, 0, "Truffnotes")
                assertTrue("Expected search matches in generated PDF", matches >= 1)
            } finally {
                pfd.close()
            }
        }
    }
}
