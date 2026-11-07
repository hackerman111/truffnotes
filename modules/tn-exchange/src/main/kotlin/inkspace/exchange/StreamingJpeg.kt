package inkspace.exchange

import java.io.OutputStream
import kotlin.math.*

/** Baseline 8-bit 4:4:4 JPEG, bounded to eight source rows; the output stream remains caller-owned. */
object StreamingJpeg {
    private val basis=Array(8) { frequency -> DoubleArray(8) { position ->
        .5 * (if(frequency==0) 1/sqrt(2.0) else 1.0) * cos((2*position+1)*frequency*PI/16)
    } }
    private val zigzag=(0..14).flatMap { sum ->
        val xs=(0..7).filter { sum-it in 0..7 }
        (if(sum%2==0) xs else xs.reversed()).map { x -> (sum-x)*8+x }
    }
    private val acSymbols=listOf(0,0xf0)+(0..15).flatMap { run -> (1..10).map { size -> (run shl 4) or size } }
    private val acCodes=acSymbols.withIndex().associate { it.value to it.index }

    fun write(output: OutputStream,width: Int,height: Int,pixels: (Int,Int)->IntArray) {
        require(width in 1..32768 && height in 1..32768)
        fun word(value: Int) { output.write(value ushr 8);output.write(value and 255) }
        fun marker(value: Int) { output.write(255);output.write(value) }
        fun segment(value: Int,bytes: ByteArray) { marker(value);word(bytes.size+2);output.write(bytes) }
        marker(0xd8)
        segment(0xe0,byteArrayOf(74,70,73,70,0,1,1,0,0,1,0,1,0,0))
        segment(0xdb,byteArrayOf(0)+ByteArray(64) {8})
        segment(0xc0,byteArrayOf(8,(height ushr 8).toByte(),height.toByte(),(width ushr 8).toByte(),width.toByte(),3,1,0x11,0,2,0x11,0,3,0x11,0))
        val dcCounts=ByteArray(16).apply {this[3]=12}
        val acCounts=ByteArray(16).apply {this[7]=162.toByte()}
        segment(0xc4,byteArrayOf(0)+dcCounts+ByteArray(12) {it.toByte()}+byteArrayOf(0x10)+acCounts+acSymbols.map {it.toByte()}.toByteArray())
        segment(0xda,byteArrayOf(3,1,0,2,0,3,0,0,63,0))
        var pending=0L;var bitCount=0
        fun bits(value: Int,count: Int) {
            if(count==0)return
            pending=(pending shl count) or (value.toLong() and ((1L shl count)-1));bitCount+=count
            while(bitCount>=8) {
                bitCount-=8
                val byte=((pending ushr bitCount) and 255).toInt()
                output.write(byte);if(byte==255)output.write(0)
            }
            pending=pending and ((1L shl bitCount)-1)
        }
        fun category(value: Int): Int = if(value==0) 0 else 32-Integer.numberOfLeadingZeros(abs(value))
        fun amplitude(value: Int,size: Int) = if(value>=0)value else value+(1 shl size)-1
        val previous=IntArray(3)
        val samples=DoubleArray(64);val horizontal=DoubleArray(64);val coefficients=IntArray(64)
        var top=0
        while(top<height) {
            val rows=minOf(8,height-top)
            val tile=pixels(top,rows)
            require(tile.size==width*rows) {"JPEG source dimensions mismatch"}
            for(left in 0 until width step 8) for(channel in 0..2) {
                for(y in 0..7)for(x in 0..7) {
                    val argb=tile[minOf(y,rows-1)*width+minOf(left+x,width-1)]
                    val alpha=(argb ushr 24)/255.0
                    val red=(argb ushr 16 and 255)*alpha+255*(1-alpha)
                    val green=(argb ushr 8 and 255)*alpha+255*(1-alpha)
                    val blue=(argb and 255)*alpha+255*(1-alpha)
                    samples[y*8+x]=when(channel) {
                        0 -> .299*red+.587*green+.114*blue-128
                        1 -> -.168736*red-.331264*green+.5*blue
                        else -> .5*red-.418688*green-.081312*blue
                    }
                }
                for(y in 0..7)for(u in 0..7) { var sum=0.0;for(x in 0..7)sum+=samples[y*8+x]*basis[u][x];horizontal[y*8+u]=sum }
                for(v in 0..7)for(u in 0..7) { var sum=0.0;for(y in 0..7)sum+=horizontal[y*8+u]*basis[v][y];coefficients[v*8+u]=(sum/8).roundToInt() }
                val delta=coefficients[0]-previous[channel];previous[channel]=coefficients[0]
                val size=category(delta);check(size<=11)
                bits(size,4);bits(amplitude(delta,size),size)
                var run=0
                for(index in 1..63) {
                    val value=coefficients[zigzag[index]]
                    if(value==0) {run++;continue}
                    while(run>=16) {bits(acCodes.getValue(0xf0),8);run-=16}
                    val length=category(value);check(length<=10)
                    bits(acCodes.getValue((run shl 4) or length),8);bits(amplitude(value,length),length);run=0
                }
                if(run>0)bits(acCodes.getValue(0),8)
            }
            top+=rows
        }
        if(bitCount>0)bits((1 shl (8-bitCount))-1,8-bitCount)
        marker(0xd9)
    }
}
