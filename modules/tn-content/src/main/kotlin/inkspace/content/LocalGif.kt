package inkspace.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import java.io.File
import java.io.InputStream

class GifDescriptor(val width: Int, val height: Int, frameDelaysMs: List<Int>) {
    val frameDelaysMs: List<Int> = java.util.List.copyOf(frameDelaysMs)
    val decodedBytes: Long get() = width.toLong() * height * 4L * (frameDelaysMs.size + 2L)
    val durationMs: Long get() = frameDelaysMs.sumOf { it.toLong() }
}

/** Parses container blocks and counts full-canvas buffers before invoking a native GIF codec. */
object GifPreflight {
    fun inspect(source: InputStream, maxFrames: Int = 120, maxDecodedBytes: Long = 32L * 1024 * 1024): GifDescriptor {
        require(maxFrames in 1..120 && maxDecodedBytes in 1..32L * 1024 * 1024)
        val reader = Reader(source)
        val signature = CharArray(6) { reader.byte().toChar() }.concatToString()
        require(signature == "GIF87a" || signature == "GIF89a") { "Invalid GIF signature" }
        val width = reader.short(); val height = reader.short()
        require(width > 0 && height > 0 && width.toLong() * height * 12L <= maxDecodedBytes) { "GIF canvas exceeds cap" }
        val packed = reader.byte(); reader.skip(2)
        if (packed and 0x80 != 0) reader.skip(3 * (1 shl ((packed and 7) + 1)))
        val delays = ArrayList<Int>()
        var delay = 100
        while (true) {
            when (reader.byte()) {
                0x3b -> { require(delays.isNotEmpty()) { "GIF contains no frames" }; return GifDescriptor(width, height, delays) }
                0x21 -> {
                    val type = reader.byte()
                    if (type == 0xf9) {
                        require(reader.byte() == 4)
                        reader.byte(); delay = (reader.short() * 10).coerceAtLeast(20)
                        reader.byte(); require(reader.byte() == 0)
                    } else reader.blocks()
                }
                0x2c -> {
                    val x = reader.short(); val y = reader.short(); val frameWidth = reader.short(); val frameHeight = reader.short()
                    require(frameWidth > 0 && frameHeight > 0 && x + frameWidth <= width && y + frameHeight <= height)
                    require(delays.size < maxFrames && width.toLong() * height * 4L * (delays.size + 3L) <= maxDecodedBytes) { "GIF decoded frame budget exceeded" }
                    val flags = reader.byte()
                    if (flags and 0x80 != 0) reader.skip(3 * (1 shl ((flags and 7) + 1)))
                    require(reader.byte() in 2..8) { "Invalid GIF LZW code size" }
                    reader.blocks()
                    delays.add(delay); delay = 100
                }
                else -> throw IllegalArgumentException("Invalid GIF block")
            }
        }
    }
    private class Reader(private val stream: InputStream) {
        private var count = 0L
        fun byte(): Int {
            require(count++ < 32L * 1024 * 1024) { "GIF source cap exceeded" }
            return stream.read().also { require(it >= 0) { "Truncated GIF" } }
        }
        fun short(): Int = byte() or (byte() shl 8)
        fun skip(size: Int) { repeat(size) { byte() } }
        fun blocks() { while (true) { val size = byte(); if (size == 0) return; skip(size) } }
    }
}

class LocalGif(file: File, val posterFrame: Int = 0, maxDecodedBytes: Long = 32L * 1024 * 1024) : AutoCloseable {
    val descriptor = file.inputStream().use { GifPreflight.inspect(it,maxDecodedBytes=maxDecodedBytes) }
    private var movie: Movie? = Movie.decodeFile(file.absolutePath)
        ?: throw IllegalArgumentException("GIF codec rejected source")
    private var elapsedMs = 0L
    init { require(posterFrame in descriptor.frameDelaysMs.indices) }
    fun advance(deltaMs: Long, visible: Boolean) {
        require(deltaMs >= 0)
        if (visible && descriptor.frameDelaysMs.size > 1) elapsedMs = (elapsedMs + deltaMs % descriptor.durationMs) % descriptor.durationMs
    }
    fun seekElapsed(elapsedMs: Long) { require(elapsedMs >= 0); this.elapsedMs=elapsedMs % descriptor.durationMs }
    fun draw(canvas: Canvas, visible: Boolean = true, staticExport: Boolean = false) {
        val active = movie ?: throw IllegalStateException("GIF player closed")
        val posterTime = descriptor.frameDelaysMs.take(posterFrame).sum()
        active.setTime(if (staticExport || !visible) posterTime else elapsedMs.toInt())
        active.draw(canvas, 0f, 0f)
    }
    fun poster(): Bitmap {
        val bitmap = Bitmap.createBitmap(descriptor.width, descriptor.height, Bitmap.Config.ARGB_8888)
        return try { draw(Canvas(bitmap), staticExport = true); bitmap } catch (error: Exception) { bitmap.recycle(); throw error }
    }
    override fun close() { movie = null }
}
