package inkspace.exchange

import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.jupiter.api.Test
import kotlin.test.*

class F14JpegTest {
    @Test fun tiled_jpeg_decodes_in_independent_decoder_with_bounded_rows() {
        val width=257; val height=73
        val stream=ByteArrayOutputStream()
        var next=0
        StreamingJpeg.write(stream,width,height) { top,rows ->
            assertEquals(next,top);next+=rows;assertTrue(rows in 1..8)
            IntArray(width*rows) { index -> val x=index%width;val y=top+index/width; 0xff000000.toInt() or ((x*255/(width-1)) shl 16) or ((y*255/(height-1)) shl 8) or 90 }
        }
        assertEquals(height,next)
        val image=ImageIO.read(stream.toByteArray().inputStream())
        assertEquals(width,image.width);assertEquals(height,image.height)
        for ((x,y) in listOf(0 to 0,128 to 36,256 to 72)) {
            val pixel=image.getRGB(x,y)
            assertTrue(kotlin.math.abs((pixel ushr 16 and 255)-x*255/(width-1)) < 12)
            assertTrue(kotlin.math.abs((pixel ushr 8 and 255)-y*255/(height-1)) < 12)
            assertTrue(kotlin.math.abs((pixel and 255)-90)<12)
        }
    }
}
