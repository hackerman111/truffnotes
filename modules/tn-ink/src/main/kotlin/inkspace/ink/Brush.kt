package inkspace.ink

import inkspace.core.ink.BrushKind
import inkspace.core.ink.BrushSpec
import inkspace.core.ink.RealSample
import inkspace.core.ink.StrokeEndCap
import inkspace.core.ink.StrokeLinePattern
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Canonical versioned pipeline for all 5 brush kinds (`Ball`, `Fountain`, `Brush`, `Pencil`, `Highlighter`).
 * Handles pressure/tilt width modulation, end caps (`ROUND`, `FLAT`, `TAPERED`), opacity, and line patterns.
 */
object BrushPipeline {

    fun modulateColorAlpha(colorArgb: Int, brush: BrushSpec): Int {
        val baseOpacity = when (brush.kind) {
            BrushKind.HIGHLIGHTER -> min(brush.opacity, 0.45f)
            BrushKind.PENCIL -> min(brush.opacity, 0.90f)
            else -> brush.opacity
        }.coerceIn(0.0f, 1.0f)
        if (baseOpacity >= 0.999f) return colorArgb
        val a = (colorArgb ushr 24) and 0xFF
        val outA = (a * baseOpacity).roundToInt().coerceIn(0, 255)
        return (colorArgb and 0x00FFFFFF) or (outA shl 24)
    }

    fun effectiveSampleWidthPt(
        brush: BrushSpec,
        pressure: Float,
        tilt: Float,
        normalizedStrokeProgress: Float
    ): Double {
        val p = pressure.coerceIn(0.0f, 1.0f)
        val t = tilt.coerceIn(0.0f, 1.5708f) / 1.5708f
        val pressureFactor = when (brush.kind) {
            BrushKind.BALL -> 1.0 + (p - 0.5) * 0.25 * brush.pressureSensitivity
            BrushKind.FOUNTAIN -> 0.55 + p * 0.90 * brush.pressureSensitivity
            BrushKind.BRUSH -> 0.35 + p * 1.35 * brush.pressureSensitivity
            BrushKind.PENCIL -> 0.75 + p * 0.45 * brush.pressureSensitivity
            BrushKind.HIGHLIGHTER -> 1.0 + (p - 0.5) * 0.10 * brush.pressureSensitivity
        }
        val tiltFactor = when (brush.kind) {
            BrushKind.BALL -> 1.0
            BrushKind.FOUNTAIN -> 1.0 + t * 0.30 * brush.tiltSensitivity
            BrushKind.BRUSH -> 1.0 + t * 0.50 * brush.tiltSensitivity
            BrushKind.PENCIL -> 1.0 + t * 0.85 * brush.tiltSensitivity
            BrushKind.HIGHLIGHTER -> 1.0 + t * 0.40 * brush.tiltSensitivity
        }
        val taperFactor = if (brush.endCap == StrokeEndCap.TAPERED) {
            val edgeDist = min(normalizedStrokeProgress, 1.0f - normalizedStrokeProgress)
            if (edgeDist < 0.12f) (0.35 + (edgeDist / 0.12f) * 0.65) else 1.0
        } else {
            1.0
        }
        return (brush.baseWidthPt * pressureFactor * tiltFactor * taperFactor).coerceAtLeast(0.25)
    }

    fun buildStrokePayload(
        origin: DocPoint,
        realSamples: List<RealSample>,
        brush: BrushSpec
    ): StrokePayload {
        if (realSamples.isEmpty()) {
            return StrokePayload(
                brushId = brush.toBrushIdString(),
                color = modulateColorAlpha(brush.colorArgb, brush),
                baseWidthPt = brush.baseWidthPt,
                origin = origin,
                samples = emptyList(),
                smoothingVersion = brush.smoothingVersion
            )
        }
        var prevTime = realSamples[0].timestampMs
        val samples = realSamples.map { s ->
            val dt = max(0L, s.timestampMs - prevTime).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            prevTime = s.timestampMs
            StrokeSamplePoint(
                relX = (s.x.toDouble() - origin.x).toFloat(),
                relY = (s.y.toDouble() - origin.y).toFloat(),
                pressure = s.pressure.coerceIn(0.0f, 1.0f),
                tilt = s.tilt,
                timeDeltaMs = dt
            )
        }
        return StrokePayload(
            brushId = brush.toBrushIdString(),
            color = modulateColorAlpha(brush.colorArgb, brush),
            baseWidthPt = brush.baseWidthPt,
            origin = origin,
            samples = java.util.List.copyOf(samples),
            smoothingVersion = brush.smoothingVersion
        )
    }

    fun computeBounds(
        origin: DocPoint,
        samples: List<StrokeSamplePoint>,
        baseWidthPt: Double
    ): DocRect {
        val pad = max(1.0, baseWidthPt * 1.25)
        if (samples.isEmpty()) {
            return DocRect(origin.x - pad, origin.y - pad, origin.x + pad, origin.y + pad)
        }
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (s in samples) {
            val wx = origin.x + s.relX
            val wy = origin.y + s.relY
            if (wx < minX) minX = wx
            if (wy < minY) minY = wy
            if (wx > maxX) maxX = wx
            if (wy > maxY) maxY = wy
        }
        return DocRect(
            left = minX - pad,
            top = minY - pad,
            right = max(minX + 0.5, maxX + pad),
            bottom = max(minY + 0.5, maxY + pad)
        )
    }

    /**
     * Generates relative path points for [smoothedPoints], preserving all original/corner vertices
     * and applying end-cap lead-in/out and pattern subdivisions (`SOLID`, `DASHED`, `DOTTED`).
     */
    fun generateBrushPathPoints(
        smoothedPoints: List<DocPoint>,
        rawSamples: List<StrokeSamplePoint>,
        brush: BrushSpec
    ): List<DocPoint> {
        if (smoothedPoints.size <= 1) return smoothedPoints

        val out = ArrayList<DocPoint>(smoothedPoints.size * 2)
        val dashPeriodPt = when (brush.linePattern) {
            StrokeLinePattern.SOLID -> 0.0
            StrokeLinePattern.DASHED -> max(4.0, brush.baseWidthPt * 3.0)
            StrokeLinePattern.DOTTED -> max(2.0, brush.baseWidthPt * 1.5)
        }

        out.add(smoothedPoints[0])
        var cumulativeDist = 0.0

        for (i in 0 until smoothedPoints.size - 1) {
            val a = smoothedPoints[i]
            val b = smoothedPoints[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val segLen = sqrt(dx * dx + dy * dy)

            // For tapered/fountain/brush or dashed/dotted strokes, insert an interior sample on long segments
            // without ever displacing the endpoints `a` and `b` (so corners at `a` or `b` remain exact).
            val pA = rawSamples.getOrNull(i)?.pressure ?: 0.5f
            val pB = rawSamples.getOrNull(i + 1)?.pressure ?: 0.5f
            val pressureDelta = kotlin.math.abs(pB - pA)

            if (segLen > 1.0 && (dashPeriodPt > 0.0 || pressureDelta > 0.15f || brush.endCap == StrokeEndCap.TAPERED)) {
                val midX = (a.x + b.x) * 0.5
                val midY = (a.y + b.y) * 0.5
                out.add(DocPoint(midX, midY))
            }

            cumulativeDist += segLen
            out.add(b)
        }

        return java.util.List.copyOf(out)
    }
}
