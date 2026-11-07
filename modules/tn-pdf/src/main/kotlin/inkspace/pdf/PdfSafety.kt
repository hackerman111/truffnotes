package inkspace.pdf

import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class PdfPageTransform(val cropBox: DocRect, val rotation: Int) {
    init {
        require(cropBox.width in 0.001..1_000_000.0 && cropBox.height in 0.001..1_000_000.0)
        require(rotation in listOf(0, 90, 180, 270))
    }
    val width: Double get() = if (rotation % 180 == 0) cropBox.width else cropBox.height
    val height: Double get() = if (rotation % 180 == 0) cropBox.height else cropBox.width

    fun pdfToDoc(point: DocPoint): DocPoint {
        val x = point.x - cropBox.left
        val y = cropBox.bottom - point.y
        return when (rotation) {
            90 -> DocPoint(cropBox.height - y, x)
            180 -> DocPoint(cropBox.width - x, cropBox.height - y)
            270 -> DocPoint(y, cropBox.width - x)
            else -> DocPoint(x, y)
        }
    }

    fun docToPdf(point: DocPoint): DocPoint {
        val unrotated = when (rotation) {
            90 -> DocPoint(point.y, cropBox.height - point.x)
            180 -> DocPoint(cropBox.width - point.x, cropBox.height - point.y)
            270 -> DocPoint(cropBox.width - point.y, point.x)
            else -> point
        }
        return DocPoint(unrotated.x + cropBox.left, cropBox.bottom - unrotated.y)
    }
}

fun tileByteSize(width: Int, height: Int): Long {
    require(width in 1..4096 && height in 1..4096) { "Invalid tile dimensions" }
    val bytes = width.toLong() * height.toLong() * 4L
    require(bytes <= 8_388_608L) { "Tile exceeds 8 MiB budget" }
    return bytes
}

data class StagedPdf(val file: File, val bytes: Long, val sha256: String)

/** Caller owns the input; destination must be new and private to the importer. */
fun stagePdf(input: InputStream, destination: File, maxBytes: Long = 268_435_456L): StagedPdf {
    require(maxBytes > 0L)
    require(!destination.exists()) { "Staging must not overwrite an existing asset" }
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    var created = false
    val header = ByteArray(5)
    var headerBytes = 0
    try {
        Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            created = true
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    // InputStream implementations may return zero; force forward progress.
                    val byte = input.read()
                    if (byte < 0) break
                    buffer[0] = byte.toByte()
                }
                val length = if (count == 0) 1 else count
                require(length.toLong() <= maxBytes - total) { "PDF exceeds staging byte budget" }
                for (index in 0 until minOf(length, 5 - headerBytes)) header[headerBytes++] = buffer[index]
                output.write(buffer, 0, length)
                digest.update(buffer, 0, length)
                total += length
            }
        }
        require(headerBytes == 5 && header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) { "Missing PDF header" }
        return StagedPdf(destination, total, digest.digest().joinToString("") { "%02x".format(it) })
    } catch (failure: Throwable) {
        if (created) destination.delete()
        throw failure
    }
}

/** PDFium reports an absent box with a non-null zero-area sentinel. */
fun resolvePdfPageBox(crop: inkspace.core.unit.DocRect, media: inkspace.core.unit.DocRect): inkspace.core.unit.DocRect {
    if (crop.width > 0 && crop.height > 0) return crop
    require(media.width > 0 && media.height > 0) { "PDF page has no valid CropBox or MediaBox" }
    return media
}
