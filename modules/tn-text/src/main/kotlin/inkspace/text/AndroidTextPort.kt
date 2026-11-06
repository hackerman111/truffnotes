package inkspace.text

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.text.style.LeadingMarginSpan
import android.text.style.BulletSpan
import inkspace.core.text.*
import inkspace.core.unit.DocRect
import inkspace.core.error.TextError
import inkspace.core.outcome.Outcome
import inkspace.core.payload.TextPayload
import inkspace.core.ports.TextLayout
import inkspace.core.ports.TextLayoutRequest
import inkspace.core.ports.TextPort
import kotlin.math.ceil

/** Owns the exact glyph positions replayed by Canvas and used for hit testing. */
class TextPaintPlan internal constructor(val layout: StaticLayout, override val estimatedBytes: Long) : TextPaintArtifact {
    override fun draw(target: Any) { require(target is Canvas); layout.draw(target) }
    override fun offsetAt(x: Float, y: Int): Int {
        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x)
        return TextInputMap(layout.text.toString()).floor(offset)
    }
    override fun cursorRect(offset: Int): DocRect {
        require(TextInputMap(layout.text.toString()).isBoundary(offset))
        val line = layout.getLineForOffset(offset)
        val x = layout.getPrimaryHorizontal(offset).toDouble()
        return DocRect(x, layout.getLineTop(line).toDouble(), x + 1.0, layout.getLineBottom(line).toDouble())
    }
    override fun rasterize(scale: Double, maxPixels: Int): TextRaster = rasterizeRegion(scale, maxPixels,
        DocRect(0.0, 0.0, layout.width.toDouble(), layout.height.toDouble()))
    override fun rasterizeRegion(scale: Double, maxPixels: Int, region: DocRect): TextRaster {
        require(scale.isFinite() && scale > 0 && maxPixels > 0 && region.width > 0 && region.height > 0)
        val width = ceil(region.width * scale).toLong().coerceAtLeast(1L)
        val height = ceil(region.height * scale).toLong().coerceAtLeast(1L)
        require(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE && width * height <= minOf(maxPixels, 2_097_152)) { "Text raster budget exceeded" }
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale.toFloat(), scale.toFloat())
            canvas.translate(-region.left.toFloat(), -region.top.toFloat())
            draw(canvas)
            val pixels = IntArray((width * height).toInt())
            bitmap.getPixels(pixels, 0, width.toInt(), 0, 0, width.toInt(), height.toInt())
            TextRaster(width.toInt(), height.toInt(), pixels)
        } finally { bitmap.recycle() }
    }
    val overflowsHeight: (Int) -> Boolean = { height -> layout.height > height }
}

class AndroidTextPort(private val cacheByteCap: Long = 2L * 1024 * 1024, private val fonts: FontRepository? = null) : TextPort {
    private data class Key(val payload: TextPayload, val width: Int)
    private val cache = LinkedHashMap<Key, TextPaintPlan>(16, 0.75f, true)
    var cachedBytes: Long = 0
        private set

    init { require(cacheByteCap >= 0) }

    @Synchronized
    fun paintPlan(payload: TextPayload, widthPt: Double): TextPaintPlan {
        require(widthPt.isFinite() && widthPt > 0 && widthPt <= 100_000)
        require(payload.text.length <= 1_000_000 && payload.fontSizePt <= 10_000)
        val width = ceil(widthPt).toInt()
        val key = Key(payload.copy(runs = java.util.List.copyOf(payload.runs)), width)
        cache[key]?.let { return it }
        val text = SpannableString(payload.text)
        val map = TextInputMap(payload.text)
        for (run in payload.runs) {
            require(run.start <= run.end && map.isBoundary(run.start) && map.isBoundary(run.end))
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            if (run.bold || run.italic) text.setSpan(StyleSpan(
                if (run.bold && run.italic) Typeface.BOLD_ITALIC else if (run.bold) Typeface.BOLD else Typeface.ITALIC
            ), run.start, run.end, flags)
            if (run.underline) text.setSpan(UnderlineSpan(), run.start, run.end, flags)
            if (run.strike) text.setSpan(StrikethroughSpan(), run.start, run.end, flags)
            run.highlight?.let { text.setSpan(BackgroundColorSpan(it), run.start, run.end, flags) }
            run.fontSizePt?.let { require(it.isFinite() && it in 0.1..10000.0); text.setSpan(RelativeSizeSpan((it / payload.fontSizePt).toFloat()), run.start, run.end, flags) }
            if (run.fontFamily != null || run.fontAsset != null) text.setSpan(TypefaceSpan(
                fonts?.resolve(run.fontAsset, run.fontFamily) ?: Typeface.create(run.fontFamily ?: payload.fontFamily, Typeface.NORMAL)
            ), run.start, run.end, flags)
            run.color?.let { text.setSpan(ForegroundColorSpan(it), run.start, run.end, flags) }
        }
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        var start = 0
        var ordinal = 1
        while (start < text.length) {
            val newline = payload.text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline + 1
            if (payload.paragraph.indentPt > 0) text.setSpan(LeadingMarginSpan.Standard(payload.paragraph.indentPt), start, end, flags)
            when (payload.paragraph.list) {
                TextListKind.BULLET -> text.setSpan(BulletSpan(8), start, end, flags)
                TextListKind.NUMBERED -> text.setSpan(NumberedParagraphSpan(ordinal++, payload.fontSizePt.toInt()), start, end, flags)
                TextListKind.NONE -> Unit
            }
            start = end
        }
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = payload.fontSizePt.toFloat(); color = payload.fontColor
            typeface = fonts?.resolve(payload.fontAsset, payload.fontFamily) ?: Typeface.create(payload.fontFamily, Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(when (payload.paragraph.alignment) {
                TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            })
            .setJustificationMode(if (payload.paragraph.alignment == TextAlignment.JUSTIFY) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .setLineSpacing(payload.paragraph.lineSpacingPt, payload.paragraph.lineSpacingMultiplier)
            .setTextDirection(if (payload.isRtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false).build()
        val plan = TextPaintPlan(layout, 256L + text.length * 4L + layout.lineCount * 64L)
        if (plan.estimatedBytes <= cacheByteCap) {
            while (cachedBytes + plan.estimatedBytes > cacheByteCap && cache.isNotEmpty()) {
                val oldest = cache.entries.first()
                cachedBytes -= oldest.value.estimatedBytes
                cache.remove(oldest.key)
            }
            cache[key] = plan
            cachedBytes += plan.estimatedBytes
        }
        return plan
    }

    override fun layout(request: TextLayoutRequest): Outcome<TextLayout, TextError> = try {
        val plan = paintPlan(request.payload ?: TextPayload(request.text, request.fontSizePt, isRtl = request.isRtl), request.maxWidthPt)
        val layout = plan.layout
        val lines = (0 until layout.lineCount).map { layout.text.subSequence(layout.getLineStart(it), layout.getLineEnd(it)).toString() }
        Outcome.Success(TextLayout(layout.width.toDouble(), layout.height.toDouble(), layout.lineCount, lines, plan.estimatedBytes, paintPlan = plan))
    } catch (error: IllegalArgumentException) {
        Outcome.Failure(TextError.ShapingFailed(error.message ?: "Invalid layout"))
    }

    @Synchronized fun clear() { cache.clear(); cachedBytes = 0 }
}

private class NumberedParagraphSpan(private val ordinal: Int, private val sizePt: Int) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = sizePt * 3
    override fun drawLeadingMargin(canvas: Canvas, paint: android.graphics.Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout) {
        if (first) canvas.drawText("$ordinal.", x.toFloat(), baseline.toFloat(), paint)
    }
}
