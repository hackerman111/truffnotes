package inkspace.content

/** Bounds are checked from headers, before allocating a pixel buffer. */
data class ImageDecodePlan(
    val sampleSize: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val rotationDegrees: Int,
    val mirrorHorizontal: Boolean,
    val orientedWidth: Int,
    val orientedHeight: Int
) {
    val decodedBytes: Long get() = decodedWidth.toLong() * decodedHeight * 4L
    companion object {
        fun create(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int, exifOrientation: Int = 1,
                   byteCap: Long = 32L * 1024 * 1024): ImageDecodePlan {
            require(width > 0 && height > 0 && requestedWidth > 0 && requestedHeight > 0 && byteCap >= 4)
            require(width.toLong() * height <= 100_000_000L) { "Image exceeds 100MP import limit" }
            require(exifOrientation in 1..8)
            val swapped = exifOrientation in 5..8
            val orientedWidth = if (swapped) height else width
            val orientedHeight = if (swapped) width else height
            var sample = 1
            fun pixels() = ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample)
            while ((orientedWidth.toLong() / (sample * 2L) >= requestedWidth &&
                    orientedHeight.toLong() / (sample * 2L) >= requestedHeight) || pixels() * 4 > byteCap) {
                require(sample <= (1 shl 29)) { "Decode sample overflow" }
                sample *= 2
            }
            return ImageDecodePlan(sample, ((width.toLong() + sample - 1) / sample).toInt(),
                ((height.toLong() + sample - 1) / sample).toInt(),
                when (exifOrientation) { 3, 4 -> 180; 5, 6 -> 90; 7, 8 -> 270; else -> 0 },
                exifOrientation in setOf(2, 4, 5, 7), orientedWidth, orientedHeight)
        }
    }
}

class GifPlaybackBudget(private val maxFrames: Int = 120, private val maxDecodedBytes: Long = 32L * 1024 * 1024) {
    var decodedBytes: Long = 0
        private set
    var frameCount: Int = 0
        private set
    init { require(maxFrames > 0 && maxDecodedBytes > 0) }
    fun admit(frameBytes: Long): Boolean {
        if (frameBytes <= 0 || frameCount >= maxFrames || frameBytes > maxDecodedBytes - decodedBytes) return false
        frameCount++; decodedBytes += frameBytes
        return true
    }
    fun shouldAnimate(visible: Boolean): Boolean = visible && frameCount > 1
}
