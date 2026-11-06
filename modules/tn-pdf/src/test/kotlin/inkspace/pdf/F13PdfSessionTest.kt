package inkspace.pdf

import inkspace.core.id.AssetId
import inkspace.core.pdf.PdfHandle
import inkspace.core.pdf.PdfReply
import inkspace.core.ports.PdfRequest
import inkspace.core.unit.DocRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class F13PdfSessionTest {
    @Test fun bounded_queue_and_service_restart_reject_stale_work() {
        val session = PdfSession(maxQueueDepth = 1)
        assertTrue(session.trySubmit(PdfRequest.Open(AssetId("asset"))).isSuccess)
        assertTrue(session.trySubmit(PdfRequest.Open(AssetId("asset"))).isFailure)
        val request = session.take()!!
        val old = PdfHandle(1, session.epoch)
        assertTrue(session.complete(request, PdfReply.Opened(old, 2)).isSuccess)
        assertIs<PdfReply.Opened>(session.poll()!!.second)
        val tile = PdfRequest.Tile(old, 0, 10, 10, DocRect(0.0, 0.0, 20.0, 20.0))
        assertTrue(session.trySubmit(tile).isSuccess)
        val pending = session.take()!!
        session.serviceDied()
        assertTrue(session.complete(pending, PdfReply.Tile(old, 10, 10, ByteArray(400))).isFailure)
        assertTrue(session.trySubmit(tile).isFailure)
        session.close()
        assertTrue(session.trySubmit(PdfRequest.Open(AssetId("asset"))).isFailure)
    }

    @Test fun reply_bounds_and_buffer_ownership() {
        val session = PdfSession()
        assertTrue(session.trySubmit(PdfRequest.Open(AssetId("asset"))).isSuccess)
        val request = session.take()!!
        assertTrue(session.complete(request, PdfReply.Opened(PdfHandle(1, session.epoch), Int.MAX_VALUE)).isFailure)
        val bytes = ByteArray(16) { 3 }
        val tile = PdfReply.Tile(PdfHandle(1, 1), 2, 2, bytes)
        bytes[0] = 7
        val copied = tile.copyRgba()
        copied[0] = 9
        assertEquals(3, tile.copyRgba()[0].toInt())
        assertFailsWith<IllegalArgumentException> { PdfReply.Tile(PdfHandle(1, 1), 5000, 5000, byteArrayOf()) }
    }
}
