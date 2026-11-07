package inkspace.exchange

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** RGBA8 PNG, bounded by one raster strip plus 32 KiB compression buffer. */
object StreamingPng {
    fun write(output: OutputStream, width: Int, height: Int, rowsPerTile: Int = 64,
        pixels: (top: Int, rows: Int) -> IntArray) {
        require(width in 1..32768 && height in 1..32768 && rowsPerTile > 0)
        require(width.toLong() * minOf(rowsPerTile, height) <= 262144) { "Raster strip budget exceeded" }
        val data = DataOutputStream(output)
        data.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(kind: String, bytes: ByteArray, count: Int = bytes.size) {
            val name = kind.toByteArray(Charsets.US_ASCII)
            data.writeInt(count); data.write(name); data.write(bytes, 0, count)
            val crc = CRC32(); crc.update(name); crc.update(bytes, 0, count); data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply {
            writeInt(width); writeInt(height); write(byteArrayOf(8,6,0,0,0))
        } }.toByteArray()
        chunk("IHDR", header)
        val deflater = Deflater(6)
        val compressed = ByteArray(32768)
        fun drain() {
            while (!deflater.needsInput()) {
                val count = deflater.deflate(compressed)
                if (count > 0) chunk("IDAT", compressed, count) else break
            }
        }
        try {
            val row = ByteArray(1 + width * 4)
            var top = 0
            while (top < height) {
                val rows = minOf(rowsPerTile, height - top)
                val argb = pixels(top, rows)
                if (argb.size != width * rows) throw ExchangeException("Wrong raster strip size")
                for (y in 0 until rows) {
                    for (x in 0 until width) {
                        val pixel = argb[y * width + x]
                        val at = 1 + x * 4
                        row[at] = (pixel ushr 16).toByte(); row[at + 1] = (pixel ushr 8).toByte()
                        row[at + 2] = pixel.toByte(); row[at + 3] = (pixel ushr 24).toByte()
                    }
                    deflater.setInput(row); drain()
                }
                top += rows
            }
            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(compressed)
                if (count <= 0) throw ExchangeException("PNG compressor made no progress")
                chunk("IDAT", compressed, count)
            }
            chunk("IEND", byteArrayOf())
            data.flush()
        } finally { deflater.end() }
    }
}
