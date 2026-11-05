package inkspace.ink

import inkspace.core.ink.RealSample
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.unit.DocPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Versioned corner-preserving stroke smoother (`smoothingVersion = 1`) and `±8192 pt` local origin chunker.
 */
object CornerPreservingSmoother {

    private const val COS_CORNER_THRESHOLD: Double = 0.5 // ~60 degrees turning angle
    const val MAX_LOCAL_CHUNK_OFFSET_PT: Double = 8192.0

    fun isIntentionalCorner(
        prevX: Double,
        prevY: Double,
        currX: Double,
        currY: Double,
        nextX: Double,
        nextY: Double
    ): Boolean {
        val ux = currX - prevX
        val uy = currY - prevY
        val vx = nextX - currX
        val vy = nextY - currY
        val lenU = sqrt(ux * ux + uy * uy)
        val lenV = sqrt(vx * vx + vy * vy)
        if (lenU < 1e-4 || lenV < 1e-4) return true
        val cosTheta = (ux * vx + uy * vy) / (lenU * lenV)
        return cosTheta <= COS_CORNER_THRESHOLD
    }

    /**
     * Smooths relative `StrokeSamplePoint`s according to [smoothingVersion] and [smoothingStrength]
     * while preserving endpoints and intentional corners (`> 60°` turning angle) verbatim.
     */
    fun smoothRelativePoints(
        samples: List<StrokeSamplePoint>,
        smoothingVersion: Int = 1,
        smoothingStrength: Float = 0.5f
    ): List<DocPoint> {
        return smoothRelativeDocPoints(
            points = samples.map { DocPoint(it.relX.toDouble(), it.relY.toDouble()) },
            smoothingVersion = smoothingVersion,
            smoothingStrength = smoothingStrength
        )
    }

    fun smoothRelativeDocPoints(
        points: List<DocPoint>,
        smoothingVersion: Int = 1,
        smoothingStrength: Float = 0.5f
    ): List<DocPoint> {
        val n = points.size
        if (n == 0) return emptyList()
        if (n <= 2 || smoothingStrength <= 1e-3f || smoothingVersion < 1) {
            return java.util.List.copyOf(points)
        }

        val alpha = (smoothingStrength.coerceIn(0.0f, 1.0f) * 0.45).coerceIn(0.0, 0.45)
        val out = ArrayList<DocPoint>(n)
        out.add(points[0])

        for (i in 1 until n - 1) {
            val prev = points[i - 1]
            val curr = points[i]
            val next = points[i + 1]

            if (isIntentionalCorner(prev.x, prev.y, curr.x, curr.y, next.x, next.y)) {
                // Intentional corner: preserve vertex coordinates verbatim
                out.add(curr)
            } else {
                val sx = (1.0 - alpha) * curr.x + (alpha * 0.5) * (prev.x + next.x)
                val sy = (1.0 - alpha) * curr.y + (alpha * 0.5) * (prev.y + next.y)
                out.add(DocPoint(sx, sy))
            }
        }

        out.add(points[n - 1])
        return java.util.List.copyOf(out)
    }

    /**
     * Converts a sequence of [RealSample]s into one or more `(origin, List<StrokeSamplePoint>)` chunks
     * so relative `Float` coordinates never exceed `±8192 pt` from their chunk origin.
     */
    fun chunkRealSamplesByOriginEnvelope(
        realSamples: List<RealSample>,
        maxLocalOffsetPt: Double = MAX_LOCAL_CHUNK_OFFSET_PT,
        maxSamplesPerChunk: Int = 64_000
    ): List<Pair<DocPoint, List<StrokeSamplePoint>>> {
        if (realSamples.isEmpty()) return emptyList()
        val result = ArrayList<Pair<DocPoint, List<StrokeSamplePoint>>>()

        var currentOrigin = DocPoint(realSamples[0].x.toDouble(), realSamples[0].y.toDouble())
        var currentList = ArrayList<StrokeSamplePoint>()
        var prevTimestamp = realSamples[0].timestampMs

        for (i in realSamples.indices) {
            val s = realSamples[i]
            val wx = s.x.toDouble()
            val wy = s.y.toDouble()
            var dx = wx - currentOrigin.x
            var dy = wy - currentOrigin.y

            if (currentList.isNotEmpty() &&
                (abs(dx) > maxLocalOffsetPt || abs(dy) > maxLocalOffsetPt || currentList.size >= maxSamplesPerChunk)
            ) {
                result.add(currentOrigin to java.util.List.copyOf(currentList))
                currentOrigin = DocPoint(wx, wy)
                currentList = ArrayList()
                dx = 0.0
                dy = 0.0
            }

            val dt = max(0L, s.timestampMs - prevTimestamp).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            prevTimestamp = s.timestampMs
            currentList.add(
                StrokeSamplePoint(
                    relX = dx.toFloat(),
                    relY = dy.toFloat(),
                    pressure = s.pressure.coerceIn(0.0f, 1.0f),
                    tilt = s.tilt,
                    timeDeltaMs = dt
                )
            )
        }

        if (currentList.isNotEmpty()) {
            result.add(currentOrigin to java.util.List.copyOf(currentList))
        }
        return result
    }
}
