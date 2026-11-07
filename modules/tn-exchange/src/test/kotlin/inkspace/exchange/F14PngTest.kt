package inkspace.exchange

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.InflaterInputStream
import kotlin.test.*
import org.junit.jupiter.api.Test

class F14PngTest {
    @Test fun png_streams_bounded_rows_with_exact_pixels() {
        val bytes = ByteArrayOutputStream()
        var largestRequest = 0
        StreamingPng.write(bytes, 7, 5, rowsPerTile = 2) { top, rows ->
            largestRequest = maxOf(largestRequest, rows)
            IntArray(7 * rows) { index -> 0xff000000.toInt() or ((top + index / 7) shl 16) or (index % 7) }
        }
        assertEquals(2, largestRequest)
        val input = DataInputStream(bytes.toByteArray().inputStream())
        assertContentEquals(byteArrayOf(-119,80,78,71,13,10,26,10), ByteArray(8).also(input::readFully))
        val compressed = ByteArrayOutputStream()
        while (input.available() > 0) {
            val size = input.readInt()
            val kind = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val chunk = ByteArray(size).also(input::readFully)
            input.readInt()
            if (kind == "IDAT") compressed.write(chunk)
        }
        val raw = InflaterInputStream(compressed.toByteArray().inputStream()).readBytes()
        assertEquals(5 * (1 + 7 * 4), raw.size)
        for (y in 0 until 5) for (x in 0 until 7) {
            val at = y * 29 + 1 + x * 4
            assertEquals(y, raw[at].toInt() and 255)
            assertEquals(x, raw[at+2].toInt() and 255)
            assertEquals(255, raw[at+3].toInt() and 255)
        }
    }
}
