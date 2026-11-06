package inkspace.content

import android.graphics.*
import android.media.ExifInterface
import inkspace.core.render.TileArtifact
import inkspace.core.unit.DocRect
import inkspace.core.id.AssetId
import java.io.File

class DecodedImage(val bitmap: Bitmap, val exifOrientation: Int) : AutoCloseable {
    fun raster(asset: AssetId? = null, bounds: DocRect = DocRect(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())): TileArtifact {
        require(bitmap.width.toLong() * bitmap.height * 4 <= 16L * 1024 * 1024) { "Raster transfer cap" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return TileArtifact("image-${asset?.value}-${bounds}", assetId = asset, docBounds = bounds, widthPx = bitmap.width, heightPx = bitmap.height, argbPixels = pixels)
    }
    override fun close() { bitmap.recycle() }
}

data class ImageHeader(val width: Int, val height: Int, val exifOrientation: Int)

class AndroidImageDecoder(private val assets: StreamingAssetStager) {
    fun header(asset: AssetId): ImageHeader = header(assets.localFile(asset))
    fun header(file: File): ImageHeader {
        require(file.length() in 1..268_435_456L)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 100_000_000L) { "Invalid image dimensions or image exceeds 100MP" }
        val orientation = try { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).coerceIn(1, 8) } catch (_: Exception) { 1 }
        return ImageHeader(options.outWidth, options.outHeight, orientation)
    }
    fun decode(asset: AssetId, requestedWidth: Int, requestedHeight: Int, byteCap: Long = 32L * 1024 * 1024): DecodedImage {
        val file = assets.localFile(asset)
        val header = header(file)
        // Reserve another output buffer when orientation requires a raster transform.
        val plan = ImageDecodePlan.create(header.width, header.height, requestedWidth, requestedHeight,
            header.exifOrientation, if (header.exifOrientation == 1) byteCap else byteCap / 2)
        val options = BitmapFactory.Options().apply { inSampleSize = plan.sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: throw IllegalArgumentException("Image codec rejected source")
        if (bitmap.allocationByteCount.toLong() > byteCap) { bitmap.recycle(); throw IllegalArgumentException("Decoded image exceeds cap") }
        return DecodedImage(orient(bitmap, plan), header.exifOrientation)
    }
    fun decodeTile(asset: AssetId, source: Rect, requestedWidth: Int, requestedHeight: Int,
                   byteCap: Long = 32L * 1024 * 1024): DecodedImage {
        val header = header(asset)
        require(source.left >= 0 && source.top >= 0 && source.right <= header.width && source.bottom <= header.height && source.width() > 0 && source.height() > 0)
        val plan = ImageDecodePlan.create(source.width(), source.height(), requestedWidth, requestedHeight, header.exifOrientation,
            if (header.exifOrientation == 1) byteCap else byteCap / 2)
        val decoder = BitmapRegionDecoder.newInstance(assets.localFile(asset).absolutePath, false)
            ?: throw IllegalArgumentException("Image cannot be tiled")
        val bitmap = try {
            decoder.decodeRegion(source, BitmapFactory.Options().apply { inSampleSize = plan.sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw IllegalArgumentException("Image tile decode failed")
        } finally { decoder.recycle() }
        if (bitmap.allocationByteCount.toLong() > byteCap) { bitmap.recycle(); throw IllegalArgumentException("Decoded tile exceeds cap") }
        return DecodedImage(orient(bitmap, plan), header.exifOrientation)
    }
    private fun orient(bitmap: Bitmap, plan: ImageDecodePlan): Bitmap {
        if (plan.rotationDegrees == 0 && !plan.mirrorHorizontal) return bitmap
        val matrix = Matrix().apply {
            postRotate(plan.rotationDegrees.toFloat())
            if (plan.mirrorHorizontal) postScale(-1f, 1f)
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }
}
