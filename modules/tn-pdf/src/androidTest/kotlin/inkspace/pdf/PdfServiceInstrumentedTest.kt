package inkspace.pdf

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import inkspace.core.id.AssetId
import inkspace.core.pdf.PdfReply
import inkspace.core.ports.PdfRequest
import inkspace.core.unit.DocRect
import inkspace.pdf.android.AndroidPdfPort
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PdfServiceInstrumentedTest {
    @Test fun isolated_pdfium_open_tile_text_search_links_outline_close() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "fixture-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("pdf/text.pdf").use { input -> source.outputStream().use(input::copyTo) }
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        val replies = LinkedBlockingQueue<PdfReply>()
        val bridge = AndroidPdfPort(context, { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) }, { _, reply -> replies.add(reply) })
        try {
            bridge.connect()
            assertTrue(bridge.trySubmit(PdfRequest.Open(AssetId("fixture"))).isSuccess)
            val opened = replies.poll(20, TimeUnit.SECONDS) as PdfReply.Opened
            assertEquals(1, opened.pageCount)
            fun reply(request: PdfRequest): PdfReply {
                assertTrue(bridge.trySubmit(request).isSuccess)
                return requireNotNull(replies.poll(10, TimeUnit.SECONDS))
            }
            val info = reply(PdfRequest.Info(opened.handle, 0)) as PdfReply.PageInfo
            assertEquals(200.0, info.page.cropBox.width, 0.01)
            val text = reply(PdfRequest.Text(opened.handle, 0)) as PdfReply.Text
            assertTrue(text.spans.joinToString("") { it.text }.contains("Hello PDF"))
            assertTrue((reply(PdfRequest.Search(opened.handle, 0, "Hello")) as PdfReply.Text).spans.isNotEmpty())
            val tile = reply(PdfRequest.Tile(opened.handle, 0, 200, 200, DocRect(0.0, 0.0, 200.0, 200.0))) as PdfReply.Tile
            assertEquals(160000, tile.byteSize)
            assertTrue((reply(PdfRequest.Links(opened.handle, 0)) as PdfReply.Links).links.isNotEmpty())
            assertTrue((reply(PdfRequest.Outline(opened.handle)) as PdfReply.Outline).entries.isNotEmpty())
            assertTrue(reply(PdfRequest.Close(opened.handle)) is PdfReply.Closed)
            assertArrayEquals(hash, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        } finally { bridge.close(); source.delete() }
    }

    @Test fun scan_returns_empty_and_damaged_input_fails_recoverably() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "scan-${System.nanoTime()}.pdf")
        val replies = LinkedBlockingQueue<PdfReply>()
        val bridge = AndroidPdfPort(context, { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) }, { _, reply -> replies.add(reply) })
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("pdf/scan.pdf").use { input -> source.outputStream().use(input::copyTo) }
            bridge.connect()
            bridge.trySubmit(PdfRequest.Open(AssetId("scan")))
            val opened = replies.poll(20, TimeUnit.SECONDS) as PdfReply.Opened
            bridge.trySubmit(PdfRequest.Text(opened.handle, 0))
            assertTrue((replies.poll(10, TimeUnit.SECONDS) as PdfReply.Text).spans.isEmpty())
            bridge.trySubmit(PdfRequest.Close(opened.handle))
            assertTrue(replies.poll(10, TimeUnit.SECONDS) is PdfReply.Closed)
            source.writeText("%PDF-1.7\nbroken")
            bridge.trySubmit(PdfRequest.Open(AssetId("broken")))
            assertTrue(replies.poll(10, TimeUnit.SECONDS) is PdfReply.Failed)
        } finally { bridge.close(); source.delete() }
    }

    @Test fun password_rotation_crop_missing_font_and_huge_dimension_guards() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "corpus-${System.nanoTime()}.pdf")
        val replies = LinkedBlockingQueue<PdfReply>()
        val bridge = AndroidPdfPort(context, { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) }, { _, reply -> replies.add(reply) })
        fun load(name: String) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("pdf/$name.pdf").use { input -> source.outputStream().use(input::copyTo) }
        }
        fun reply(request: PdfRequest): PdfReply {
            assertTrue(bridge.trySubmit(request).isSuccess)
            return requireNotNull(replies.poll(20, TimeUnit.SECONDS))
        }
        try {
            bridge.connect()
            load("encrypted")
            assertTrue(reply(PdfRequest.Open(AssetId("encrypted"), "wrong")) is PdfReply.Failed)
            val encrypted = reply(PdfRequest.Open(AssetId("encrypted"), "fixture-password")) as PdfReply.Opened
            assertEquals(1, encrypted.pageCount)
            reply(PdfRequest.Close(encrypted.handle))
            load("rotated-crop")
            val rotated = reply(PdfRequest.Open(AssetId("rotated"))) as PdfReply.Opened
            val info = reply(PdfRequest.Info(rotated.handle, 0)) as PdfReply.PageInfo
            assertEquals(90, info.page.rotation)
            assertEquals(300.0, info.page.cropBox.width, 0.01)
            reply(PdfRequest.Close(rotated.handle))
            load("missing-font")
            val missing = reply(PdfRequest.Open(AssetId("missing"))) as PdfReply.Opened
            assertTrue((reply(PdfRequest.Text(missing.handle, 0)) as PdfReply.Text).spans.joinToString("") { it.text }.contains("Fallback"))
            reply(PdfRequest.Close(missing.handle))
            load("named-links")
            val named = reply(PdfRequest.Open(AssetId("named"))) as PdfReply.Opened
            assertEquals(2, named.pageCount)
            assertEquals(1, (reply(PdfRequest.Links(named.handle, 0)) as PdfReply.Links).links.single().targetPage)
            assertEquals(1, (reply(PdfRequest.Outline(named.handle)) as PdfReply.Outline).entries.single().pageIndex)
            reply(PdfRequest.Close(named.handle))
            load("huge")
            val huge = reply(PdfRequest.Open(AssetId("huge"))) as PdfReply.Opened
            assertTrue(reply(PdfRequest.Info(huge.handle, 0)) is PdfReply.Failed)
            reply(PdfRequest.Close(huge.handle))
        } finally { bridge.close(); source.delete() }
    }
}
