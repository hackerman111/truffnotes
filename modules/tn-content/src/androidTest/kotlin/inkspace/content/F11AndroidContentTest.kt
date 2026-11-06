package inkspace.content

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class F11AndroidContentTest {
    @Test fun bounded_png_decode_tile_and_source_unchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "f11-${System.nanoTime()}").apply { mkdirs() }
        try {
            val stager = StreamingAssetStager(directory)
            val source = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val bytes = java.io.ByteArrayOutputStream().use { output -> source.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
            source.recycle()
            val asset = stager.stage(bytes.inputStream(), 100000, "png")
            val decoder = AndroidImageDecoder(stager)
            assertEquals(32, decoder.header(asset.assetId).width)
            decoder.decode(asset.assetId, 8, 8).use { decoded -> assertTrue(decoded.bitmap.width <= 32); assertEquals(Color.RED, decoded.bitmap.getPixel(0, 0)) }
            decoder.decodeTile(asset.assetId, Rect(0, 0, 8, 8), 8, 8).use { decoded -> assertEquals(8, decoded.bitmap.width); assertEquals(8, decoded.raster(asset.assetId).widthPx) }
            assertArrayEquals(bytes, stager.open(asset.assetId).use { it.readBytes() })
        } finally { directory.deleteRecursively() }
    }
    @Test fun gif_native_poster_and_hidden_pause() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "gif-${System.nanoTime()}")
        try {
            source.writeBytes(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", android.util.Base64.DEFAULT))
            LocalGif(source).use { gif ->
                assertEquals(1, gif.descriptor.width)
                gif.advance(1000, false)
                val poster = gif.poster(); assertEquals(1, poster.width); poster.recycle()
            }
        } finally { source.delete() }
    }
}
