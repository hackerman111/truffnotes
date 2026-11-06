package inkspace.pdf

import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class F13PdfSafetyTest {
    @Test fun crop_rotation_roundtrip() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val transform = PdfPageTransform(DocRect(10.0, 20.0, 210.0, 320.0), rotation)
            val point = DocPoint(31.0, 47.0)
            assertEquals(point, transform.docToPdf(transform.pdfToDoc(point)))
        }
        val transform = PdfPageTransform(DocRect(10.0, 20.0, 210.0, 320.0), 0)
        assertEquals(DocPoint(0.0, 0.0), transform.pdfToDoc(DocPoint(10.0, 320.0)))
    }

    @Test fun invalid_dimensions_rejected_before_allocation() {
        assertFailsWith<IllegalArgumentException> { PdfPageTransform(DocRect(0.0, 0.0, 0.0, 40.0), 0) }
        assertFailsWith<IllegalArgumentException> { tileByteSize(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { tileByteSize(4096, 4096) }
        assertEquals(4_194_304L, tileByteSize(1024, 1024))
    }

    @Test fun nonseekable_staging_preserves_hash_and_cleans_on_failure() {
        val dir = Files.createTempDirectory("pdf-staging-test").toFile()
        try {
            val bytes = "%PDF-1.7\nfixture".toByteArray()
            val file = dir.resolve("source.pdf")
            val staged = stagePdf(ByteArrayInputStream(bytes), file, 100L)
            assertContentEquals(bytes, file.readBytes())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, staged.sha256)
            val failed = dir.resolve("large.pdf")
            assertFailsWith<IllegalArgumentException> { stagePdf(ByteArrayInputStream(bytes), failed, 4L) }
            assertFalse(failed.exists())
            assertFailsWith<IllegalArgumentException> { stagePdf(ByteArrayInputStream("not PDF".toByteArray()), failed, 100L) }
            assertFalse(failed.exists())
            assertFailsWith<IllegalArgumentException> { stagePdf(ByteArrayInputStream(bytes), file, 100L) }
            assertContentEquals(bytes, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
