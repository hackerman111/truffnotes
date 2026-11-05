package inkspace.spike.ports

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.RequiresApi
import inkspace.spike.model.CommittedStroke
import inkspace.spike.model.Sample
import inkspace.spike.model.StrokeDraft
import inkspace.spike.model.TextPaintPlan

/**
 * Port responsible for computing stroke geometry from raw samples.
 */
interface GeometryPort {
    fun generateStrokePath(samples: List<Sample>, baseWidth: Float): Path
}

/**
 * Port responsible for unified canvas rendering across Surface, Bitmap, and PDF.
 */
interface RenderPort {
    fun drawScene(
        canvas: Canvas,
        committedStrokes: List<CommittedStroke>,
        activeDraft: StrokeDraft?,
        geometryPort: GeometryPort
    )
}

/**
 * Default implementation of stroke geometry with pressure-driven line segment quad smoothing.
 */
class DefaultGeometryPort : GeometryPort {
    override fun generateStrokePath(samples: List<Sample>, baseWidth: Float): Path {
        val path = Path()
        if (samples.isEmpty()) return path

        if (samples.size == 1) {
            val s0 = samples[0]
            val r = (baseWidth * s0.pressure.coerceIn(0.1f, 2.0f)) / 2.0f
            path.addCircle(s0.x, s0.y, r.coerceAtLeast(1.0f), Path.Direction.CW)
            return path
        }

        path.moveTo(samples[0].x, samples[0].y)
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val midX = (prev.x + curr.x) / 2.0f
            val midY = (prev.y + curr.y) / 2.0f
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        val last = samples.last()
        path.lineTo(last.x, last.y)
        return path
    }
}

/**
 * Unified canvas replay renderer.
 */
class DefaultRenderPort : RenderPort {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF0D47A1.toInt() // Dark blue draft
    }

    override fun drawScene(
        canvas: Canvas,
        committedStrokes: List<CommittedStroke>,
        activeDraft: StrokeDraft?,
        geometryPort: GeometryPort
    ) {
        // Draw committed strokes
        for (stroke in committedStrokes) {
            strokePaint.color = stroke.color
            strokePaint.strokeWidth = stroke.baseWidth
            val path = geometryPort.generateStrokePath(stroke.samples, stroke.baseWidth)
            canvas.drawPath(path, strokePaint)
        }

        // Draw active ephemeral draft
        if (activeDraft != null && activeDraft.samples.isNotEmpty()) {
            val lastPressure = activeDraft.samples.last().pressure.coerceIn(0.2f, 2.0f)
            draftPaint.strokeWidth = 4.0f * lastPressure
            val path = geometryPort.generateStrokePath(activeDraft.samples, 4.0f)
            canvas.drawPath(path, draftPaint)
        }
    }
}

/**
 * Text layout helper using StaticLayout.Builder (API 29+).
 */
object SpikeTextPort {
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createLayout(plan: TextPaintPlan): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = plan.textSizePx
            color = plan.textColor
        }

        return StaticLayout.Builder
            .obtain(plan.text, 0, plan.text.length, paint, plan.maxWidthPx.coerceAtLeast(1))
            .setAlignment(if (plan.isRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0.0f, 1.2f)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
    }
}
