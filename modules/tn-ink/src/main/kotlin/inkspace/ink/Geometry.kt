package inkspace.ink

import inkspace.core.error.GeometryError
import inkspace.core.ink.BrushSpec
import inkspace.core.outcome.Outcome
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.GeometryRequest
import inkspace.core.ports.PathArtifact
import inkspace.core.unit.DocRect
import kotlin.math.max

/**
 * Production implementation of [GeometryPort] in `:tn-ink`.
 * Converts a [GeometryRequest] into a canonical [PathArtifact] using [CornerPreservingSmoother]
 * and [BrushPipeline] across all 5 brush kinds (`Ball`, `Fountain`, `Brush`, `Pencil`, `Highlighter`).
 */
class InkGeometryPort : GeometryPort {

    override fun paths(request: GeometryRequest): Outcome<PathArtifact, GeometryError> {
        if (!request.baseWidthPt.isFinite() || request.baseWidthPt <= 0.0) {
            return Outcome.Failure(
                GeometryError.ComputationFailed("Invalid baseWidthPt=${request.baseWidthPt} for ${request.strokeId.value}")
            )
        }

        val spec = BrushSpec.fromBrushIdString(
            brushId = request.brushId,
            baseWidthPt = request.baseWidthPt,
            smoothingVersion = request.smoothingVersion
        )

        val smoothed = CornerPreservingSmoother.smoothRelativeDocPoints(
            points = request.samples,
            smoothingVersion = request.smoothingVersion,
            smoothingStrength = spec.smoothingStrength
        )

        val pathPoints = BrushPipeline.generateBrushPathPoints(
            smoothedPoints = smoothed,
            rawSamples = emptyList(),
            brush = spec
        )

        val pad = max(1.0, request.baseWidthPt * 1.25)
        val bounds = if (pathPoints.isEmpty()) {
            DocRect(
                request.origin.x - pad,
                request.origin.y - pad,
                request.origin.x + pad,
                request.origin.y + pad
            )
        } else {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (pt in pathPoints) {
                val wx = request.origin.x + pt.x
                val wy = request.origin.y + pt.y
                if (wx < minX) minX = wx
                if (wy < minY) minY = wy
                if (wx > maxX) maxX = wx
                if (wy > maxY) maxY = wy
            }
            DocRect(minX - pad, minY - pad, max(minX + 0.5, maxX + pad), max(minY + 0.5, maxY + pad))
        }

        val segCount = max(1, pathPoints.size - 1)
        val estimatedBytes = 96L + pathPoints.size.toLong() * 16L

        return Outcome.Success(
            PathArtifact(
                strokeId = request.strokeId,
                bounds = bounds,
                segmentCount = segCount,
                origin = request.origin,
                relativePoints = pathPoints,
                estimatedBytes = estimatedBytes
            )
        )
    }
}
