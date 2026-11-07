package inkspace.pdf
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Test
import kotlin.test.*
class PdfPageBoxRegressionTest {
    @Test fun zoom_pdf_rotation() {
        val crop = DocRect(10.0, 20.0, 210.0, 320.0)
        for (rotation in listOf(0, 90, 180, 270)) {
            val transform = PdfPageTransform(crop, rotation)
            val pdf = inkspace.core.unit.DocPoint(73.0, 99.0)
            val doc = transform.pdfToDoc(pdf)
            val zoom = inkspace.core.workspace.ZoomViewState(region = DocRect(0.0, 0.0, transform.width, transform.height))
            val camera = zoom.camera(1000, 600)
            val restored = transform.docToPdf(camera.viewToDoc(camera.docToView(doc)))
            assertEquals(pdf.x, restored.x, .01)
            assertEquals(pdf.y, restored.y, .01)
        }
    }
    @Test fun absentCropBoxSentinelFallsBackToMediaBox() {
        val media = DocRect(0.0, 0.0, 612.0, 792.0)
        assertEquals(media, resolvePdfPageBox(DocRect(-1.0,-1.0,-1.0,-1.0), media))
    }
    @Test fun validCropBoxIsPreserved() {
        val crop = DocRect(10.0,20.0,200.0,300.0)
        assertEquals(crop, resolvePdfPageBox(crop,DocRect(0.0,0.0,612.0,792.0)))
    }
    @Test fun invalidCropAndMediaAreRejected() {
        assertFailsWith<IllegalArgumentException> { resolvePdfPageBox(DocRect(-1.0,-1.0,-1.0,-1.0),DocRect(0.0,0.0,0.0,0.0)) }
    }
}
