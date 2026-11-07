package inkspace.pdf

import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import inkspace.pdf.android.PdfCompositionWriter
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class PdfCompositionTest {
    @Test fun `standard annotation types have appearances and flatten source appearances`() {
        val overlay=ByteArrayOutputStream()
        PDDocument().use { document ->
            val page=PDPage(PDRectangle(40f,40f));document.addPage(page)
            PDPageContentStream(document,page).use { stream -> stream.addRect(5f,5f,30f,30f);stream.stroke() }
            document.save(overlay)
        }
        val output=ByteArrayOutputStream()
        PdfCompositionWriter().use { writer ->
            val page=writer.page(200f,200f)
            for((index,type) in listOf("Text","Square","Circle","Ink").withIndex()) {
                writer.overlay(page,overlay.toByteArray(),type,"Note",if(type=="Ink") floatArrayOf(10f,10f,30f,30f) else null,
                    x=index*45f,y=50f,width=40f,height=40f)
            }
            writer.write(output)
        }
        PDDocument.load(output.toByteArray()).use { document ->
            assertEquals(listOf("Text","Square","Circle","Ink"),document.getPage(0).annotations.map { it.subtype })
            assertTrue(document.getPage(0).annotations.all { it.rectangle.width==40f && it.normalAppearanceStream!=null })
        }
        val evidence=java.io.File("../../docs/evidence/F14-F15/pdf-fixtures").apply { mkdirs() }
        evidence.resolve("standard-types.pdf").writeBytes(output.toByteArray())
        val source=Files.createTempFile("source-annotations",".pdf").toFile()
        try {
            source.writeBytes(output.toByteArray())
            val flattened=ByteArrayOutputStream()
            PdfCompositionWriter().use { writer ->
                val page=writer.page(200f,200f);writer.source(page,source,0,0f,0f,200f,200f);writer.write(flattened)
            }
            PDDocument.load(flattened.toByteArray()).use { assertTrue(it.getPage(0).annotations.isEmpty()) }
            evidence.resolve("source-annotations-flattened.pdf").writeBytes(flattened.toByteArray())
        } finally { source.delete() }
    }

    @Test fun `rotated nonzero crop is normalized before placement`() {
        val source=java.io.File("src/androidTest/assets/pdf/rotated-crop.pdf")
        val output=ByteArrayOutputStream()
        PdfCompositionWriter().use { writer ->
            val page=writer.page(220f,300f)
            writer.source(page,source,0,0f,0f,220f,300f,mapNavigation=true)
            writer.write(output)
        }
        PDDocument.load(output.toByteArray()).use { document ->
            assertEquals(220f,document.getPage(0).mediaBox.width)
            assertEquals(300f,document.getPage(0).mediaBox.height)
        }
        val evidence=java.io.File("../../docs/evidence/F14-F15/pdf-fixtures").apply { mkdirs() }
        evidence.resolve("source-rotated-crop.pdf").writeBytes(output.toByteArray())
    }

    @Test fun `text source remains text with affine placement`() {
        val source=java.io.File("src/androidTest/assets/pdf/text.pdf")
        val output=ByteArrayOutputStream()
        PdfCompositionWriter().use { writer ->
            val page=writer.page(300f,300f)
            writer.source(page,source,0,20f,30f,200f,200f,mapNavigation=true,
                transform=floatArrayOf(1f,0.2f,0.1f,1f,0f,0f))
            writer.write(output)
        }
        PDDocument.load(output.toByteArray()).use { document ->
            val forms=document.getPage(0).resources.xObjectNames.map { document.getPage(0).resources.getXObject(it) }
            assertTrue(forms.all { it is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject })
            val bytes=(forms.single() as com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject).contents.readBytes().decodeToString()
            assertTrue(bytes.contains("BT"));assertTrue(bytes.contains("TJ") || bytes.contains("Tj"))
        }
        val evidence=java.io.File("../../docs/evidence/F14-F15/pdf-fixtures").apply { mkdirs() }
        evidence.resolve("source-text-affine.pdf").writeBytes(output.toByteArray())
    }

    @Test fun `source vectors navigation and genuine annotation appearances survive composition`() {
        val directory = Files.createTempDirectory("pdf-composition").toFile()
        try {
            val source = directory.resolve("source.pdf")
            PDDocument().use { document ->
                val a = PDPage(PDRectangle(200f,200f)); val b = PDPage(PDRectangle(200f,200f))
                document.addPage(a); document.addPage(b)
                PDPageContentStream(document,a).use { stream -> stream.addRect(20f,20f,80f,60f); stream.stroke() }
                a.annotations.add(PDAnnotationLink().apply { rectangle=PDRectangle(20f,20f,40f,30f); destination=PDPageFitDestination().also { it.page=b } })
                val root=PDDocumentOutline(); document.documentCatalog.documentOutline=root
                root.addLast(PDOutlineItem().apply { title="Second page"; destination=PDPageFitDestination().also { it.page=b } })
                document.save(source)
            }
            val overlay=ByteArrayOutputStream()
            PDDocument().use { document ->
                val page=PDPage(PDRectangle(200f,200f)); document.addPage(page)
                PDPageContentStream(document,page).use { stream -> stream.moveTo(10f,100f); stream.lineTo(170f,130f); stream.stroke() }
                document.save(overlay)
            }
            for(standard in listOf(false,true)) {
                val output=ByteArrayOutputStream()
                PdfCompositionWriter().use { writer ->
                    val a=writer.page(200f,200f); val b=writer.page(200f,200f)
                    writer.source(a,source,0,0f,0f,200f,200f,mapNavigation=true)
                    writer.source(b,source,1,0f,0f,200f,200f,mapNavigation=true)
                    writer.overlay(a,overlay.toByteArray(),if(standard) "Ink" else null,ink=floatArrayOf(10f,100f,170f,130f))
                    writer.write(output)
                    assertTrue(writer.fallbackReport.isEmpty())
                }
                PDDocument.load(output.toByteArray()).use { document ->
                    assertEquals(2,document.numberOfPages)
                    val first=document.getPage(0)
                    assertTrue(first.resources.xObjectNames.any())
                    val forms=first.resources.xObjectNames.map { first.resources.getXObject(it) }
                    assertTrue(forms.all { it is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject })
                    assertTrue(forms.filterIsInstance<com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject>().any {
                        it.contents.readBytes().decodeToString().contains(" re")
                    })
                    assertEquals(if(standard) 2 else 1,first.annotations.size)
                    if(standard) {
                        val annotation=first.annotations.first { it.subtype=="Ink" }
                        assertNotNull(annotation.appearance.normalAppearance)
                        assertNotNull(annotation.cosObject.getDictionaryObject("InkList"))
                    }
                    val link=first.annotations.filterIsInstance<PDAnnotationLink>().single()
                    assertEquals(document.getPage(1).cosObject,(link.destination as PDPageFitDestination).page.cosObject)
                    assertNotNull(document.documentCatalog.documentOutline.firstChild)
                }
                val evidence=java.io.File("../../docs/evidence/F14-F15/pdf-fixtures").apply { mkdirs() }
                evidence.resolve(if(standard) "standard.pdf" else "flattened.pdf").writeBytes(output.toByteArray())
            }
        } finally { directory.deleteRecursively() }
    }
}
