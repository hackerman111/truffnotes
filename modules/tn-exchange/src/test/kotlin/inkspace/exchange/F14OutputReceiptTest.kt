package inkspace.exchange

import org.junit.jupiter.api.Test
import java.io.*
import kotlin.test.*

class F14OutputReceiptTest {
    @Test fun provider_silent_short_write_aborts_new_output() {
        val previous=byteArrayOf(9,9,9)
        val bytes=ByteArrayOutputStream();var aborted=false
        val receipt=OutputReceipt()
        val output=object: ExportOutput {
            override val replacesExisting=false
            override val atomicReplacement=false
            override fun open()=receipt.stream(object:OutputStream() { override fun write(value:Int) { if(bytes.size()<3)bytes.write(value) } })
            override fun complete()=receipt.verify(bytes.toByteArray().inputStream())
            override fun abort(){aborted=true;bytes.reset()}
        }
        assertFailsWith<ExchangeException> { SafeExport.write(output) {it.write(byteArrayOf(1,2,3,4,5))} }
        assertTrue(aborted);assertEquals(0,bytes.size());assertContentEquals(byteArrayOf(9,9,9),previous)
    }
    @Test fun exact_provider_output_has_matching_receipt() {
        val receipt=OutputReceipt();val bytes=ByteArrayOutputStream()
        receipt.stream(bytes).use {it.write(byteArrayOf(1,2,3))}
        receipt.verify(bytes.toByteArray().inputStream())
    }
}
