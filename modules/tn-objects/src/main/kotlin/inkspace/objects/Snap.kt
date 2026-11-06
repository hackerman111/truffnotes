package inkspace.objects

import inkspace.core.id.ObjectId
import inkspace.core.objects.AlignmentGuideKind
import inkspace.core.objects.AlignmentGuideLine
import inkspace.core.objects.GridKind
import inkspace.core.objects.GridSpec
import inkspace.core.objects.GuideOrientation
import inkspace.core.objects.GuideSpec
import inkspace.core.objects.ObjectAlignmentResult
import inkspace.core.objects.ProtractorOverlayState
import inkspace.core.objects.RulerEdge
import inkspace.core.objects.RulerOverlayState
import inkspace.core.objects.RulerUnit
import inkspace.core.objects.SnapConfig
import inkspace.core.objects.SnappedPointResult
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spatial snapping and precision alignment engine (`F08.5`).
 *
 * Implements:
 * - Ruler two-edge snapping (`TOP_EDGE` and `BOTTOM_EDGE`) and cm/inch unit conversions.
 * - Protractor outer arc and radial ray angle snapping.
 * - 7 grid layouts: `BLANK`, `RULED`, `SQUARED`, `DOTTED`, `MILLIMETER`, `ISOMETRIC`, `CUSTOM`.
 * - Angle snapping (default 15° steps with configurable tolerance).
 * - Multi-object edge and center alignment guides (dashed for centers, solid for edges).
 */
object SnapEngine {

    const val POINTS_PER_CM: Double = 72.0 / 2.54 // ~28.3464567 pt
    const val POINTS_PER_INCH: Double = 72.0

    fun ptToRulerUnit(points: Double, unit: RulerUnit): Double {
        return when (unit) {
            RulerUnit.CENTIMETERS -> points / POINTS_PER_CM
            RulerUnit.INCHES -> points / POINTS_PER_INCH
        }
    }

    fun rulerUnitToPt(value: Double, unit: RulerUnit): Double {
        return when (unit) {
            RulerUnit.CENTIMETERS -> value * POINTS_PER_CM
            RulerUnit.INCHES -> value * POINTS_PER_INCH
        }
    }

    fun snapPoint(
        point: DocPoint,
        config: SnapConfig,
        gridOverride: GridSpec? = null,
        rulerOverride: RulerOverlayState? = null,
        protractorOverride: ProtractorOverlayState? = null
    ): SnappedPointResult {
        var curX = point.x
        var curY = point.y
        var snappedEdge: RulerEdge? = null
        var snappedProtractor = false
        var snappedGuideId: String? = null
        var snappedGrid = false
        var rulerMeasurement: Double? = null

        val ruler = rulerOverride ?: config.ruler
        val protractor = protractorOverride ?: config.protractor
        val grid = gridOverride ?: config.grid

        // 1. Ruler snapping (highest priority)
        if (ruler.isVisible) {
            val rulerSnap = snapToRulerEdge(DocPoint(curX, curY), ruler)
            if (rulerSnap != null) {
                curX = rulerSnap.first.x
                curY = rulerSnap.first.y
                snappedEdge = rulerSnap.second
                rulerMeasurement = rulerSnap.third
            }
        }

        // 2. Protractor snapping
        if (protractor.isVisible && snappedEdge == null) {
            val protractorSnap = snapToProtractor(
                point = DocPoint(curX, curY),
                protractor = protractor
            )
            if (protractorSnap != null) {
                curX = protractorSnap.x
                curY = protractorSnap.y
                snappedProtractor = true
            }
        }

        // 3. User guides snapping
        if (snappedEdge == null && !snappedProtractor) {
            for (g in config.frozenGuides) {
                val gSnap = snapToGuide(DocPoint(curX, curY), g)
                if (gSnap != null) {
                    curX = gSnap.x
                    curY = gSnap.y
                    snappedGuideId = g.id
                    break
                }
            }
        }

        // 4. Grid snapping
        if (grid.snapEnabled && grid.kind != GridKind.BLANK && snappedEdge == null && !snappedProtractor) {
            val gridPt = snapToGrid(DocPoint(curX, curY), grid)
            if (gridPt != null) {
                curX = gridPt.x
                curY = gridPt.y
                snappedGrid = true
            }
        }

        return SnappedPointResult(
            point = DocPoint(curX, curY),
            snappedToRulerEdge = snappedEdge,
            snappedToProtractorArc = snappedProtractor,
            snappedToGuideId = snappedGuideId,
            snappedToGrid = snappedGrid,
            rulerMeasurementUnits = rulerMeasurement
        )
    }

    fun snapAngleDegrees(
        angleDegrees: Double,
        config: SnapConfig
    ): Double {
        if (!config.angleSnapEnabled) return angleDegrees
        val step = config.angleSnapStepDegrees
        val tol = config.angleSnapToleranceDegrees
        if (step <= 1e-4) return angleDegrees

        val nearestStep = round(angleDegrees / step)
        val candidate = nearestStep * step
        return if (abs(angleDegrees - candidate) <= tol) {
            candidate
        } else {
            angleDegrees
        }
    }

    fun snapToRulerEdge(
        point: DocPoint,
        ruler: RulerOverlayState
    ): Triple<DocPoint, RulerEdge, Double>? {
        val angleRad = ruler.angleDegrees * (PI / 180.0)
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)

        // Transform point into ruler's local coordinate frame
        val dx = point.x - ruler.centerDoc.x
        val dy = point.y - ruler.centerDoc.y
        val localX = dx * cosA + dy * sinA
        val localY = -dx * sinA + dy * cosA

        val halfLength = ruler.lengthPt * 0.5
        val halfThickness = ruler.thicknessPt * 0.5
        val tol = ruler.snapTolerancePt

        if (localX < -halfLength - tol || localX > halfLength + tol) {
            return null
        }

        val clampedLocalX = localX.coerceIn(-halfLength, halfLength)

        val distToTop = abs(localY - halfThickness)
        val distToBottom = abs(localY - (-halfThickness))

        val (chosenEdge, chosenY, minDist) = if (distToTop <= distToBottom) {
            Triple(RulerEdge.TOP_EDGE, halfThickness, distToTop)
        } else {
            Triple(RulerEdge.BOTTOM_EDGE, -halfThickness, distToBottom)
        }

        if (minDist > tol) return null

        val worldX = ruler.centerDoc.x + clampedLocalX * cosA - chosenY * sinA
        val worldY = ruler.centerDoc.y + clampedLocalX * sinA + chosenY * cosA

        val distAlongRulerPt = clampedLocalX + halfLength
        val units = ptToRulerUnit(distAlongRulerPt, ruler.unit)

        return Triple(DocPoint(worldX, worldY), chosenEdge, units)
    }

    fun snapToProtractor(
        point: DocPoint,
        protractor: ProtractorOverlayState
    ): DocPoint? {
        val dx = point.x - protractor.centerDoc.x
        val dy = point.y - protractor.centerDoc.y
        val currentDist = sqrt(dx * dx + dy * dy)
        var currentAngleRad = atan2(dy, dx)
        var currentAngleDeg = currentAngleRad * (180.0 / PI)

        var changed = false
        var resolvedRadius = currentDist

        // 1. Distance snapping to outer arc
        if (abs(currentDist - protractor.radiusPt) <= protractor.snapTolerancePt) {
            resolvedRadius = protractor.radiusPt
            changed = true
        }

        // 2. Angle snapping to step rays
        val step = protractor.angleSnapStepDegrees
        val relDeg = currentAngleDeg - protractor.baseAngleDegrees
        val nearestStep = round(relDeg / step)
        val candidateRelDeg = nearestStep * step
        if (abs(relDeg - candidateRelDeg) <= 4.0) {
            currentAngleDeg = protractor.baseAngleDegrees + candidateRelDeg
            currentAngleRad = currentAngleDeg * (PI / 180.0)
            changed = true
        }

        if (!changed) return null

        val worldX = protractor.centerDoc.x + resolvedRadius * cos(currentAngleRad)
        val worldY = protractor.centerDoc.y + resolvedRadius * sin(currentAngleRad)
        return DocPoint(worldX, worldY)
    }

    fun snapToGuide(
        point: DocPoint,
        guide: GuideSpec
    ): DocPoint? {
        val tol = guide.snapTolerancePt
        return when (guide.orientation) {
            GuideOrientation.HORIZONTAL -> {
                if (abs(point.y - guide.positionPt.y) <= tol) {
                    DocPoint(point.x, guide.positionPt.y)
                } else null
            }
            GuideOrientation.VERTICAL -> {
                if (abs(point.x - guide.positionPt.x) <= tol) {
                    DocPoint(guide.positionPt.x, point.y)
                } else null
            }
            GuideOrientation.ANGLED -> {
                val rad = guide.angleDegrees * (PI / 180.0)
                val cosA = cos(rad)
                val sinA = sin(rad)
                val dx = point.x - guide.positionPt.x
                val dy = point.y - guide.positionPt.y
                val perpDist = -dx * sinA + dy * cosA
                if (abs(perpDist) <= tol) {
                    val along = dx * cosA + dy * sinA
                    DocPoint(guide.positionPt.x + along * cosA, guide.positionPt.y + along * sinA)
                } else null
            }
        }
    }

    fun snapToGrid(
        point: DocPoint,
        grid: GridSpec
    ): DocPoint? {
        val tol = grid.snapTolerancePt
        when (grid.kind) {
            GridKind.BLANK -> return null

            GridKind.RULED -> {
                val spacing = grid.spacingPt.coerceAtLeast(4.0)
                val nearestY = round(point.y / spacing) * spacing
                return if (abs(point.y - nearestY) <= tol) {
                    DocPoint(point.x, nearestY)
                } else null
            }

            GridKind.SQUARED, GridKind.DOTTED, GridKind.CUSTOM -> {
                val spacing = grid.spacingPt.coerceAtLeast(4.0)
                val nearestX = round(point.x / spacing) * spacing
                val nearestY = round(point.y / spacing) * spacing
                val snapX = abs(point.x - nearestX) <= tol
                val snapY = abs(point.y - nearestY) <= tol
                return if (snapX || snapY) {
                    DocPoint(if (snapX) nearestX else point.x, if (snapY) nearestY else point.y)
                } else null
            }

            GridKind.MILLIMETER -> {
                val mmStep = POINTS_PER_CM / 10.0 // ~2.83465 pt
                val nearestX = round(point.x / mmStep) * mmStep
                val nearestY = round(point.y / mmStep) * mmStep
                val snapX = abs(point.x - nearestX) <= tol
                val snapY = abs(point.y - nearestY) <= tol
                return if (snapX || snapY) {
                    DocPoint(if (snapX) nearestX else point.x, if (snapY) nearestY else point.y)
                } else null
            }

            GridKind.ISOMETRIC -> {
                val s = grid.spacingPt.coerceAtLeast(8.0)
                val h = s * (sqrt(3.0) / 2.0)
                val row = round(point.y / h).toInt()
                val nearestY = row * h
                val rowOffset = if (row % 2 != 0) s * 0.5 else 0.0
                val relX = point.x - rowOffset
                val nearestX = round(relX / s) * s + rowOffset

                val dx = point.x - nearestX
                val dy = point.y - nearestY
                return if (sqrt(dx * dx + dy * dy) <= tol) {
                    DocPoint(nearestX, nearestY)
                } else null
            }
        }
    }

    fun alignObject(
        draggedBounds: DocRect,
        candidateObjects: List<Pair<ObjectId, DocRect>>,
        config: SnapConfig
    ): ObjectAlignmentResult {
        if (!config.alignObjectsEnabled || candidateObjects.isEmpty()) {
            return ObjectAlignmentResult(draggedBounds, 0.0, 0.0, emptyList())
        }

        var deltaX = 0.0
        var deltaY = 0.0
        val tol = config.alignObjectsTolerancePt
        val guides = ArrayList<AlignmentGuideLine>()

        val curCenterX = draggedBounds.left + draggedBounds.width * 0.5
        val curCenterY = draggedBounds.top + draggedBounds.height * 0.5

        var minDeltaX = Double.POSITIVE_INFINITY
        var bestXGuide: AlignmentGuideLine? = null

        var minDeltaY = Double.POSITIVE_INFINITY
        var bestYGuide: AlignmentGuideLine? = null

        for ((candId, cand) in candidateObjects) {
            val candCenterX = cand.left + cand.width * 0.5
            val candCenterY = cand.top + cand.height * 0.5

            // Center X
            val diffCenterX = abs(curCenterX - candCenterX)
            if (diffCenterX <= tol && diffCenterX < minDeltaX) {
                minDeltaX = diffCenterX
                deltaX = candCenterX - curCenterX
                bestXGuide = AlignmentGuideLine(
                    kind = AlignmentGuideKind.CENTER_X,
                    coordinatePt = candCenterX,
                    isDashedCenterLine = true,
                    referenceObjectId = candId
                )
            }

            // Left edge
            val diffLeft = abs(draggedBounds.left - cand.left)
            if (diffLeft <= tol && diffLeft < minDeltaX) {
                minDeltaX = diffLeft
                deltaX = cand.left - draggedBounds.left
                bestXGuide = AlignmentGuideLine(
                    kind = AlignmentGuideKind.LEFT_EDGE,
                    coordinatePt = cand.left,
                    isDashedCenterLine = false,
                    referenceObjectId = candId
                )
            }

            // Right edge
            val diffRight = abs(draggedBounds.right - cand.right)
            if (diffRight <= tol && diffRight < minDeltaX) {
                minDeltaX = diffRight
                deltaX = cand.right - draggedBounds.right
                bestXGuide = AlignmentGuideLine(
                    kind = AlignmentGuideKind.RIGHT_EDGE,
                    coordinatePt = cand.right,
                    isDashedCenterLine = false,
                    referenceObjectId = candId
                )
            }

            // Center Y
            val diffCenterY = abs(curCenterY - candCenterY)
            if (diffCenterY <= tol && diffCenterY < minDeltaY) {
                minDeltaY = diffCenterY
                deltaY = candCenterY - curCenterY
                bestYGuide = AlignmentGuideLine(
                    kind = AlignmentGuideKind.CENTER_Y,
                    coordinatePt = candCenterY,
                    isDashedCenterLine = true,
                    referenceObjectId = candId
                )
            }

            // Top edge
            val diffTop = abs(draggedBounds.top - cand.top)
            if (diffTop <= tol && diffTop < minDeltaY) {
                minDeltaY = diffTop
                deltaY = cand.top - draggedBounds.top
                bestYGuide = AlignmentGuideLine(
                    kind = AlignmentGuideKind.TOP_EDGE,
                    coordinatePt = cand.top,
                    isDashedCenterLine = false,
                    referenceObjectId = candId
                )
            }

            // Bottom edge
            val diffBottom = abs(draggedBounds.bottom - cand.bottom)
            if (diffBottom <= tol && diffBottom < minDeltaY) {
                minDeltaY = diffBottom
                deltaY = cand.bottom - draggedBounds.bottom
                bestYGuide = AlignmentGuideLine(
                    kind = AlignmentGuideKind.BOTTOM_EDGE,
                    coordinatePt = cand.bottom,
                    isDashedCenterLine = false,
                    referenceObjectId = candId
                )
            }
        }

        bestXGuide?.let { guides.add(it) }
        bestYGuide?.let { guides.add(it) }

        val snappedBounds = if (deltaX != 0.0 || deltaY != 0.0) {
            draggedBounds.translate(deltaX, deltaY)
        } else {
            draggedBounds
        }

        return ObjectAlignmentResult(
            snappedBounds = snappedBounds,
            deltaX = deltaX,
            deltaY = deltaY,
            activeGuides = guides
        )
    }
}
