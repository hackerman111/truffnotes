package inkspace.core.text

import inkspace.core.id.ObjectId
import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable

@Serializable enum class TextAlignment { START, CENTER, END, JUSTIFY }
@Serializable enum class TextListKind { NONE, BULLET, NUMBERED }
@Serializable data class ParagraphStyle(
    val alignment: TextAlignment = TextAlignment.START,
    val lineSpacingMultiplier: Float = 1f,
    val lineSpacingPt: Float = 0f,
    val indentPt: Int = 0,
    val list: TextListKind = TextListKind.NONE
) {
    init {
        require(lineSpacingMultiplier.isFinite() && lineSpacingMultiplier in 0.5f..10f)
        require(lineSpacingPt.isFinite() && lineSpacingPt in 0f..1000f)
        require(indentPt in 0..10000)
    }
}

sealed interface ImeEdit {
    val objectId: ObjectId
    val focusEpoch: Long
    data class Replace(override val objectId: ObjectId, override val focusEpoch: Long,
        val start: Int, val end: Int, val text: String, val composing: Boolean = false,
        val newCursorPosition: Int = 1) : ImeEdit
    data class Selection(override val objectId: ObjectId, override val focusEpoch: Long,
        val start: Int, val end: Int) : ImeEdit
    data class Finish(override val objectId: ObjectId, override val focusEpoch: Long, val cancel: Boolean = false) : ImeEdit
}

interface TextPaintArtifact {
    val estimatedBytes: Long
    fun draw(target: Any)
    fun offsetAt(x: Float, y: Int): Int
    fun cursorRect(offset: Int): DocRect
    fun rasterize(scale: Double, maxPixels: Int): TextRaster
    fun rasterizeRegion(scale: Double, maxPixels: Int, region: DocRect): TextRaster = rasterize(scale, maxPixels)
}

class TextRaster(val width: Int, val height: Int, pixels: IntArray) {
    private val pixels = pixels.copyOf()
    init { require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) }
    fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]
}
