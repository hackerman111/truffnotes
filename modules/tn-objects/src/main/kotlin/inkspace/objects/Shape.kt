package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.Precondition
import inkspace.core.mutation.UpdatePayload
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.PlanContext
import inkspace.core.objects.RecognitionConfig
import inkspace.core.objects.ShapeCandidate
import inkspace.core.objects.StrokeData
import inkspace.core.outcome.Outcome
import inkspace.core.payload.AnchorSide
import inkspace.core.payload.ArrowheadStyle
import inkspace.core.payload.ConnectorAnchor
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometric shape recognition and validation (`F08.2`).
 *
 * Implements deterministic fitting for all 12 [ShapeType] variants:
 * `Line`, `Arrow`, `Rectangle`, `Square`, `RoundedRectangle`, `Circle`, `Ellipse`,
 * `Triangle`, `Polygon`, `Polyline`, `Curve`, `Arc`.
 *
 * Distinguishes intentional geometric hold gestures from freehand handwriting/math/sketches,
 * strictly returning [ShapeCandidate.AmbiguousKeepOriginal] for complex handwriting.
 */
object ShapeRecognizer {

    fun recognize(
        stroke: StrokeData,
        config: RecognitionConfig = RecognitionConfig()
    ): ShapeCandidate {
        if (!config.drawAndHoldEnabled) {
            return ShapeCandidate.Rejected("Hold-to-shape is disabled")
        }
        if (!config.ignoreHoldRequirement && stroke.holdDurationMs < config.holdThresholdMs) {
            return ShapeCandidate.Rejected(
                "Hold duration ${stroke.holdDurationMs}ms below threshold ${config.holdThresholdMs}ms"
            )
        }
        val samples = stroke.frozenSamples
        if (samples.size < 2) {
            return ShapeCandidate.Rejected("Insufficient samples: ${samples.size}")
        }

        val points = ArrayList<DocPoint>(samples.size)
        for (s in samples) {
            points.add(DocPoint(stroke.origin.x + s.relX, stroke.origin.y + s.relY))
        }

        val arcLength = computeArcLength(points)
        if (arcLength < config.minShapeExtentPt) {
            return ShapeCandidate.Rejected("Stroke arc length too short: $arcLength")
        }

        val startPt = points.first()
        val endPt = points.last()
        val chordDist = distance(startPt, endPt)

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val bounds = DocRect(minX, minY, maxX, maxY)
        val maxDim = max(bounds.width, bounds.height).coerceAtLeast(1.0)
        val minDim = min(bounds.width, bounds.height).coerceAtLeast(1.0)
        val aspectRatio = minDim / maxDim

        // Check self-intersections to reject cursive handwriting loops and complex math
        val intersectionCount = countSelfIntersections(points)
        if (intersectionCount >= 2) {
            return ShapeCandidate.AmbiguousKeepOriginal("Multiple self-intersections ($intersectionCount)")
        }

        val isClosed = stroke.isClosedLoop || chordDist <= (config.closureDistanceRatio * maxDim) || chordDist <= 18.0

        val corners = detectCorners(points, 35.0, isClosed)

        if (isClosed) {
            // Closed shape family
            val signedArea = computeSignedArea(points)
            val absArea = abs(signedArea)
            val isoperimetricQuotient = (4.0 * PI * absArea) / (arcLength * arcLength)

            // Ambiguous handwriting check: small loops with high aspect ratio or tiny area
            if (absArea < 50.0 || isoperimetricQuotient < 0.25 || arcLength < 40.0) {
                return ShapeCandidate.AmbiguousKeepOriginal("Closed loop with low circularity / area")
            }

            // Triangle: 3 distinct corners
            if (corners.size == 3) {
                val triangleBounds = computePolygonBounds(corners)
                val payload = ShapePayload(
                    shapeType = ShapeType.Triangle,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt,
                    vertices = corners
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Triangle,
                    payload = payload,
                    bounds = triangleBounds,
                    confidence = 0.95
                )
            }

            // 4 corners: Rectangle, Square, or RoundedRectangle
            if (corners.size == 4) {
                val polyBounds = computePolygonBounds(corners)
                val isSquare = abs(polyBounds.width - polyBounds.height) / max(polyBounds.width, polyBounds.height) <= 0.15
                val shapeType = if (isSquare) ShapeType.Square else ShapeType.Rectangle
                val payload = ShapePayload(
                    shapeType = shapeType,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt,
                    vertices = corners
                )
                return ShapeCandidate.Recognized(
                    shapeType = shapeType,
                    payload = payload,
                    bounds = polyBounds,
                    confidence = 0.94
                )
            }

            // 5 or 6 corners: Polygon
            if (corners.size in 5..6 && arcLength >= 50.0) {
                val polyBounds = computePolygonBounds(corners)
                val payload = ShapePayload(
                    shapeType = ShapeType.Polygon,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt,
                    vertices = corners
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Polygon,
                    payload = payload,
                    bounds = polyBounds,
                    confidence = 0.90
                )
            }

            // Smooth closed loop (0, 1, or 2 minor corners)
            if (isoperimetricQuotient >= 0.70 && aspectRatio >= 0.82) {
                // Circle
                val radius = max(bounds.width, bounds.height) * 0.5
                val cx = (bounds.left + bounds.right) * 0.5
                val cy = (bounds.top + bounds.bottom) * 0.5
                val circleBounds = DocRect(cx - radius, cy - radius, cx + radius, cy + radius)
                val payload = ShapePayload(
                    shapeType = ShapeType.Circle,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Circle,
                    payload = payload,
                    bounds = circleBounds,
                    confidence = isoperimetricQuotient.coerceIn(0.85, 0.99)
                )
            }

            if (isoperimetricQuotient >= 0.55) {
                // Ellipse
                val payload = ShapePayload(
                    shapeType = ShapeType.Ellipse,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Ellipse,
                    payload = payload,
                    bounds = bounds,
                    confidence = isoperimetricQuotient.coerceIn(0.75, 0.95)
                )
            }

            return ShapeCandidate.AmbiguousKeepOriginal("Closed stroke does not fit standard geometric shapes")
        } else {
            // Open shape family
            val reversalsX = countAxisReversals(points) { it.x }
            val reversalsY = countAxisReversals(points) { it.y }
            if (reversalsX >= 3 || reversalsY >= 3) {
                return ShapeCandidate.AmbiguousKeepOriginal("Open stroke has oscillatory zig-zag / shading reversals")
            }

            val straightness = chordDist / arcLength
            val isStraight = straightness >= 0.92

            // Check if stroke forms a straight arrow with terminal barb
            val arrowDetection = checkArrowCandidate(points)
            if (arrowDetection != null) {
                val payload = ShapePayload(
                    shapeType = ShapeType.Arrow,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt,
                    vertices = listOf(startPt, arrowDetection),
                    endArrowhead = ArrowheadStyle.OPEN_ARROW
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Arrow,
                    payload = payload,
                    bounds = bounds,
                    confidence = 0.94
                )
            }

            if (isStraight) {
                val payload = ShapePayload(
                    shapeType = ShapeType.Line,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt,
                    vertices = listOf(startPt, endPt),
                    endArrowhead = ArrowheadStyle.NONE
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Line,
                    payload = payload,
                    bounds = bounds,
                    confidence = straightness.coerceIn(0.90, 0.99)
                )
            }

            // Open polyline with sharp corners: requires large extent, 1..4 corners, and straight segments
            if (corners.size in 1..4 && arcLength >= 50.0 && maxDim >= 35.0) {
                val polylineVertices = ArrayList<DocPoint>(corners.size + 2)
                polylineVertices.add(startPt)
                for (c in corners) {
                    if (distance(c, startPt) > 12.0 && distance(c, endPt) > 12.0) {
                        polylineVertices.add(c)
                    }
                }
                polylineVertices.add(endPt)
                if (polylineVertices.size in 3..6 && checkPolylineSegments(points, polylineVertices)) {
                    val payload = ShapePayload(
                        shapeType = ShapeType.Polyline,
                        strokeColor = stroke.colorArgb,
                        strokeWidthPt = stroke.baseWidthPt,
                        vertices = polylineVertices
                    )
                    return ShapeCandidate.Recognized(
                        shapeType = ShapeType.Polyline,
                        payload = payload,
                        bounds = bounds,
                        confidence = 0.91
                    )
                }
            }

            // Smooth open curve or arc
            val isArc = checkCircularArcFit(points)
            if (isArc && arcLength >= 30.0) {
                val payload = ShapePayload(
                    shapeType = ShapeType.Arc,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt,
                    vertices = listOf(startPt, endPt)
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Arc,
                    payload = payload,
                    bounds = bounds,
                    confidence = 0.88
                )
            }

            val isSmoothCurve = checkSmoothMonotonicCurve(points)
            if (isSmoothCurve && arcLength >= 30.0) {
                val midPt = points[points.size / 2]
                val payload = ShapePayload(
                    shapeType = ShapeType.Curve,
                    strokeColor = stroke.colorArgb,
                    strokeWidthPt = stroke.baseWidthPt,
                    vertices = listOf(startPt, endPt),
                    controlPoints = listOf(midPt)
                )
                return ShapeCandidate.Recognized(
                    shapeType = ShapeType.Curve,
                    payload = payload,
                    bounds = bounds,
                    confidence = 0.86
                )
            }

            return ShapeCandidate.AmbiguousKeepOriginal("Open stroke does not match line, arc, polyline, or curve")
        }
    }

    private fun countAxisReversals(points: List<DocPoint>, axis: (DocPoint) -> Double): Int {
        if (points.size < 4) return 0
        var reversals = 0
        var prevDelta = 0.0
        val step = max(1, points.size / 25)
        var i = 0
        while (i < points.size - step) {
            val delta = axis(points[i + step]) - axis(points[i])
            if (abs(delta) > 3.0) {
                if (prevDelta != 0.0 && ((delta > 0 && prevDelta < 0) || (delta < 0 && prevDelta > 0))) {
                    reversals++
                }
                prevDelta = delta
            }
            i += step
        }
        return reversals
    }

    private fun checkPolylineSegments(points: List<DocPoint>, vertices: List<DocPoint>): Boolean {
        if (vertices.size < 3) return false
        var lastIdx = 0
        for (k in 0 until vertices.size - 1) {
            val v1 = vertices[k]
            val v2 = vertices[k + 1]
            val segDist = distance(v1, v2)
            if (segDist < 15.0) return false

            var nextIdx = lastIdx
            var minDist = Double.MAX_VALUE
            for (i in lastIdx until points.size) {
                val d = distance(points[i], v2)
                if (d < minDist) {
                    minDist = d
                    nextIdx = i
                }
            }
            if (nextIdx <= lastIdx) return false

            var subArc = 0.0
            for (i in lastIdx until nextIdx) {
                subArc += distance(points[i], points[i + 1])
            }
            if (subArc > 0.0 && (segDist / subArc) < 0.85) {
                return false
            }
            lastIdx = nextIdx
        }
        return true
    }

    private fun distance(p1: DocPoint, p2: DocPoint): Double {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun computeArcLength(points: List<DocPoint>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distance(points[i], points[i + 1])
        }
        return total
    }

    private fun computeSignedArea(points: List<DocPoint>): Double {
        var area = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return area * 0.5
    }

    private fun computePolygonBounds(vertices: List<DocPoint>): DocRect {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (v in vertices) {
            if (v.x < minX) minX = v.x
            if (v.y < minY) minY = v.y
            if (v.x > maxX) maxX = v.x
            if (v.y > maxY) maxY = v.y
        }
        return DocRect(minX, minY, maxX, maxY)
    }

    private fun detectCorners(
        points: List<DocPoint>,
        cornerAngleThresholdDeg: Double,
        isClosed: Boolean = false
    ): List<DocPoint> {
        if (points.size < 5) return emptyList()
        val step = max(2, points.size / 30)
        val corners = ArrayList<DocPoint>()
        val minAngleRad = (180.0 - cornerAngleThresholdDeg) * (PI / 180.0)

        // Check closure corner if closed
        if (isClosed && points.size >= step * 3) {
            val pPrev = points[points.size - step - 1]
            val pCurr = points[0]
            val pNext = points[step]
            val v1x = pPrev.x - pCurr.x
            val v1y = pPrev.y - pCurr.y
            val v2x = pNext.x - pCurr.x
            val v2y = pNext.y - pCurr.y
            val dot = v1x * v2x + v1y * v2y
            val mag1 = sqrt(v1x * v1x + v1y * v1y)
            val mag2 = sqrt(v2x * v2x + v2y * v2y)
            if (mag1 > 2.0 && mag2 > 2.0) {
                val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)
                val angle = kotlin.math.acos(cosTheta)
                if (angle <= minAngleRad) {
                    corners.add(pCurr)
                }
            }
        }

        var i = step
        while (i < points.size - step) {
            val pPrev = points[i - step]
            val pCurr = points[i]
            val pNext = points[i + step]

            val v1x = pPrev.x - pCurr.x
            val v1y = pPrev.y - pCurr.y
            val v2x = pNext.x - pCurr.x
            val v2y = pNext.y - pCurr.y

            val dot = v1x * v2x + v1y * v2y
            val mag1 = sqrt(v1x * v1x + v1y * v1y)
            val mag2 = sqrt(v2x * v2x + v2y * v2y)

            if (mag1 > 2.0 && mag2 > 2.0) {
                val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)
                val angle = kotlin.math.acos(cosTheta)
                if (angle <= minAngleRad) {
                    corners.add(pCurr)
                    i += step * 2
                    continue
                }
            }
            i += 1
        }
        return corners
    }

    private fun checkArrowCandidate(points: List<DocPoint>): DocPoint? {
        if (points.size < 6) return null
        val n = points.size
        val shaftEndIdx = (n * 0.85).toInt().coerceIn(3, n - 2)
        val shaftStart = points.first()
        val shaftEnd = points[shaftEndIdx]
        val shaftChord = distance(shaftStart, shaftEnd)
        var shaftArc = 0.0
        for (i in 0 until shaftEndIdx) {
            shaftArc += distance(points[i], points[i + 1])
        }
        if (shaftChord / shaftArc < 0.90) return null

        val end = points.last()
        val mainDirX = shaftEnd.x - shaftStart.x
        val mainDirY = shaftEnd.y - shaftStart.y
        val barbDirX = end.x - shaftEnd.x
        val barbDirY = end.y - shaftEnd.y
        val dot = mainDirX * barbDirX + mainDirY * barbDirY
        return if (dot < 0) shaftEnd else null
    }

    private fun countSelfIntersections(points: List<DocPoint>): Int {
        var count = 0
        val n = points.size
        val step = max(1, n / 40)
        val sampled = ArrayList<DocPoint>()
        var i = 0
        while (i < n) {
            sampled.add(points[i])
            i += step
        }
        if (sampled.last() != points.last()) sampled.add(points.last())

        val sCount = sampled.size
        for (a in 0 until sCount - 1) {
            val p1 = sampled[a]
            val p2 = sampled[a + 1]
            for (b in a + 2 until sCount - 1) {
                if (a == 0 && b == sCount - 2) continue
                val p3 = sampled[b]
                val p4 = sampled[b + 1]
                if (linesIntersect(p1, p2, p3, p4)) {
                    count++
                    if (count >= 3) return count
                }
            }
        }
        return count
    }

    private fun linesIntersect(p1: DocPoint, p2: DocPoint, p3: DocPoint, p4: DocPoint): Boolean {
        fun ccw(a: DocPoint, b: DocPoint, c: DocPoint): Double {
            return (c.y - a.y) * (b.x - a.x) - (b.y - a.y) * (c.x - a.x)
        }
        val d1 = ccw(p1, p3, p4)
        val d2 = ccw(p2, p3, p4)
        val d3 = ccw(p1, p2, p3)
        val d4 = ccw(p1, p2, p4)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun checkTerminalArrowhead(points: List<DocPoint>): Boolean {
        if (points.size < 6) return false
        val n = points.size
        val end = points.last()
        val nearEnd = points[n - 3]
        val mainStart = points[0]
        val mainDirX = nearEnd.x - mainStart.x
        val mainDirY = nearEnd.y - mainStart.y
        val barbDirX = end.x - nearEnd.x
        val barbDirY = end.y - nearEnd.y
        val dot = mainDirX * barbDirX + mainDirY * barbDirY
        return dot < 0
    }

    private fun checkCircularArcFit(points: List<DocPoint>): Boolean {
        if (points.size < 8) return false
        val p1 = points.first()
        val p2 = points[points.size / 2]
        val p3 = points.last()

        val d12 = distance(p1, p2)
        val d23 = distance(p2, p3)
        val d13 = distance(p1, p3)
        if (d13 >= (d12 + d23) * 0.96) return false

        val midChord = DocPoint((p1.x + p3.x) * 0.5, (p1.y + p3.y) * 0.5)
        val sagitta = distance(p2, midChord)
        if (sagitta < 8.0 || (sagitta / d13) !in 0.1..0.8) return false

        // A circular arc must turn strictly in ONE direction (no reversals or zig-zags)
        var prevSign = 0
        val step = max(1, points.size / 10)
        var i = step
        while (i < points.size - step) {
            val a = points[i - step]
            val b = points[i]
            val c = points[i + step]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val sign = if (cross > 2.0) 1 else if (cross < -2.0) -1 else 0
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) {
                    return false // Zig-zag or S-curve cannot be a circular arc
                }
                prevSign = sign
            }
            i += step
        }
        return prevSign != 0
    }

    private fun checkSmoothMonotonicCurve(points: List<DocPoint>): Boolean {
        if (points.size < 6) return false
        var prevSign = 0
        var inflectionCount = 0
        val step = max(1, points.size / 8)
        var i = step
        while (i < points.size - step) {
            val pA = points[i - step]
            val pB = points[i]
            val pC = points[i + step]
            val v1x = pB.x - pA.x
            val v1y = pB.y - pA.y
            val v2x = pC.x - pB.x
            val v2y = pC.y - pB.y
            val cross = v1x * v2y - v1y * v2x
            val sign = if (cross > 4.0) 1 else if (cross < -4.0) -1 else 0
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) {
                    inflectionCount++
                    if (inflectionCount >= 2) return false // Oscillating handwriting!
                }
                prevSign = sign
            }
            i += step
        }
        return inflectionCount <= 1
    }
}

/**
 * Executes shape creation, vertex updates, style patches, connectors, and atomic quick-diagramming (`F08.2`, `F08.3`).
 */
object ShapePlanner {

    fun plan(
        read: DocumentRead,
        action: ObjectAction.ShapeAction,
        ctx: PlanContext = PlanContext()
    ): Outcome<EditPlan, FeatureError> {
        return when (action) {
            is ObjectAction.ShapeAction.CreateShape -> planCreateShape(read, action, ctx)
            is ObjectAction.ShapeAction.UpdateShapeVertices -> planUpdateShapeVertices(read, action, ctx)
            is ObjectAction.ShapeAction.UpdateShapeAppearance -> planUpdateShapeAppearance(read, action, ctx)
            is ObjectAction.ShapeAction.CreateConnector -> planCreateConnector(read, action, ctx)
            is ObjectAction.ShapeAction.UpdateConnectorEndpoints -> planUpdateConnectorEndpoints(read, action, ctx)
            is ObjectAction.ShapeAction.QuickDiagramCreateAndConnect -> planQuickDiagram(read, action, ctx)
        }
    }

    private fun planCreateShape(
        read: DocumentRead,
        action: ObjectAction.ShapeAction.CreateShape,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        if (read.isLayerLocked(ctx.activeLayerId)) {
            return Outcome.Failure(FeatureError.LockedTarget(ctx.activeLayerId.value, "Active layer is locked"))
        }
        if (read.objectState(action.objectId) != ResidencyState.Missing) {
            return Outcome.Failure(FeatureError.InvalidParameters("Shape with ID ${action.objectId.value} already exists"))
        }

        val mutations = ArrayList<Mutation>()
        val preconditions = ArrayList<Precondition>()

        val replaceId = action.replaceStrokeId
        if (replaceId != null) {
            val replaced = read.getObject(replaceId)
            if (replaced != null) {
                preconditions.add(Precondition(replaced.id.value, replaced.version))
                mutations.add(inkspace.core.mutation.RemoveObject(replaced.id))
            }
        }

        mutations.add(
            InsertObject(
                space = ctx.activeSpaceId,
                layer = ctx.activeLayerId,
                id = action.objectId,
                payload = action.payload,
                bounds = action.bounds,
                targetVersion = 1L
            )
        )

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Create Shape")
            )
        )
    }

    private fun planUpdateShapeVertices(
        read: DocumentRead,
        action: ObjectAction.ShapeAction.UpdateShapeVertices,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val obj = read.getObject(action.targetId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Shape ${action.targetId.value} not found"))
        if (obj.isLocked || read.isLayerLocked(obj.layer)) {
            return Outcome.Failure(FeatureError.LockedTarget(obj.id.value, "Shape or layer is locked"))
        }
        val currentPayload = obj.payload as? ShapePayload
            ?: return Outcome.Failure(FeatureError.InvalidParameters("Object is not a Shape"))

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        val allPts = action.frozenVertices + action.frozenControlPoints
        if (allPts.isEmpty()) {
            return Outcome.Failure(FeatureError.InvalidParameters("Cannot update shape with empty vertices"))
        }
        for (p in allPts) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val pad = currentPayload.strokeWidthPt * 0.5
        val nextBounds = DocRect(minX - pad, minY - pad, maxX + pad, maxY + pad)

        val nextPayload = currentPayload.copy(
            vertices = action.frozenVertices,
            controlPoints = action.frozenControlPoints
        )

        val preconditions = listOf(Precondition(obj.id.value, obj.version))
        val mutations = listOf(
            UpdatePayload(
                id = obj.id,
                newPayload = nextPayload,
                newBounds = nextBounds,
                targetVersion = obj.version + 1L
            )
        )

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Update Shape Vertices")
            )
        )
    }

    private fun planUpdateShapeAppearance(
        read: DocumentRead,
        action: ObjectAction.ShapeAction.UpdateShapeAppearance,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val obj = read.getObject(action.targetId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Shape ${action.targetId.value} not found"))
        if (obj.isLocked || read.isLayerLocked(obj.layer)) {
            return Outcome.Failure(FeatureError.LockedTarget(obj.id.value, "Shape or layer is locked"))
        }
        val currentPayload = obj.payload as? ShapePayload
            ?: return Outcome.Failure(FeatureError.InvalidParameters("Object is not a Shape"))

        val resolvedFill = if (action.clearFill) null else (action.fillColor ?: currentPayload.fillColor)
        val nextPayload = currentPayload.copy(
            shapeType = action.shapeType ?: currentPayload.shapeType,
            strokeColor = action.strokeColor ?: currentPayload.strokeColor,
            strokeWidthPt = action.strokeWidthPt ?: currentPayload.strokeWidthPt,
            fillColor = resolvedFill,
            cornerRadiusPt = action.cornerRadiusPt ?: currentPayload.cornerRadiusPt,
            startArrowhead = action.startArrowhead ?: currentPayload.startArrowhead,
            endArrowhead = action.endArrowhead ?: currentPayload.endArrowhead
        )

        val preconditions = listOf(Precondition(obj.id.value, obj.version))
        val mutations = listOf(
            UpdatePayload(
                id = obj.id,
                newPayload = nextPayload,
                newBounds = obj.bounds,
                targetVersion = obj.version + 1L
            )
        )

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Update Shape Appearance")
            )
        )
    }

    private fun planCreateConnector(
        read: DocumentRead,
        action: ObjectAction.ShapeAction.CreateConnector,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val src = read.getObject(action.sourceId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Source object ${action.sourceId.value} not found"))
        val dst = read.getObject(action.targetId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Target object ${action.targetId.value} not found"))

        if (read.isLayerLocked(ctx.activeLayerId)) {
            return Outcome.Failure(FeatureError.LockedTarget(ctx.activeLayerId.value, "Active layer is locked"))
        }

        val startPt = action.sourceAnchor.resolveWorldPoint(src.bounds)
        val endPt = action.targetAnchor.resolveWorldPoint(dst.bounds)

        val minX = min(startPt.x, endPt.x) - 4.0
        val minY = min(startPt.y, endPt.y) - 4.0
        val maxX = max(startPt.x, endPt.x) + 4.0
        val maxY = max(startPt.y, endPt.y) + 4.0
        val bounds = DocRect(minX, minY, maxX, maxY)

        val payload = ConnectorPayload(
            sourceId = action.sourceId,
            targetId = action.targetId,
            sourceAnchor = action.sourceAnchor,
            targetAnchor = action.targetAnchor,
            controlPoints = action.frozenControlPoints,
            strokeColor = action.strokeColor,
            strokeWidthPt = action.strokeWidthPt,
            isDirected = action.isDirected,
            startArrowhead = action.startArrowhead,
            endArrowhead = action.endArrowhead,
            danglingPolicy = action.danglingPolicy,
            resolvedStartPoint = startPt,
            resolvedEndPoint = endPt
        )

        val insert = InsertObject(
            space = ctx.activeSpaceId,
            layer = ctx.activeLayerId,
            id = action.connectorId,
            payload = payload,
            bounds = bounds,
            targetVersion = 1L
        )

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = emptyList(),
                mutations = listOf(insert),
                gesture = ctx.gestureId,
                label = UndoLabel("Create Connector")
            )
        )
    }

    private fun planUpdateConnectorEndpoints(
        read: DocumentRead,
        action: ObjectAction.ShapeAction.UpdateConnectorEndpoints,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val obj = read.getObject(action.connectorId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Connector ${action.connectorId.value} not found"))
        val currentPayload = obj.payload as? ConnectorPayload
            ?: return Outcome.Failure(FeatureError.InvalidParameters("Object is not a Connector"))

        val srcId = action.sourceId ?: currentPayload.sourceId
        val dstId = action.targetId ?: currentPayload.targetId

        val src = read.getObject(srcId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Source ${srcId.value} not found"))
        val dst = read.getObject(dstId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Target ${dstId.value} not found"))

        val srcAnchor = action.sourceAnchor ?: currentPayload.sourceAnchor
        val dstAnchor = action.targetAnchor ?: currentPayload.targetAnchor

        val startPt = srcAnchor.resolveWorldPoint(src.bounds)
        val endPt = dstAnchor.resolveWorldPoint(dst.bounds)

        val minX = min(startPt.x, endPt.x) - 4.0
        val minY = min(startPt.y, endPt.y) - 4.0
        val maxX = max(startPt.x, endPt.x) + 4.0
        val maxY = max(startPt.y, endPt.y) + 4.0
        val bounds = DocRect(minX, minY, maxX, maxY)

        val nextPayload = currentPayload.copy(
            sourceId = srcId,
            targetId = dstId,
            sourceAnchor = srcAnchor,
            targetAnchor = dstAnchor,
            controlPoints = action.controlPoints ?: currentPayload.controlPoints,
            danglingPolicy = action.danglingPolicy ?: currentPayload.danglingPolicy,
            resolvedStartPoint = startPt,
            resolvedEndPoint = endPt
        )

        val preconditions = listOf(Precondition(obj.id.value, obj.version))
        val mutations = listOf(
            UpdatePayload(
                id = obj.id,
                newPayload = nextPayload,
                newBounds = bounds,
                targetVersion = obj.version + 1L
            )
        )

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Update Connector Endpoints")
            )
        )
    }

    private fun planQuickDiagram(
        read: DocumentRead,
        action: ObjectAction.ShapeAction.QuickDiagramCreateAndConnect,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val src = read.getObject(action.sourceId)
            ?: return Outcome.Failure(FeatureError.EntityNotFound("Source shape ${action.sourceId.value} not found"))
        if (read.isLayerLocked(ctx.activeLayerId)) {
            return Outcome.Failure(FeatureError.LockedTarget(ctx.activeLayerId.value, "Active layer is locked"))
        }

        val newShapeInsert = InsertObject(
            space = ctx.activeSpaceId,
            layer = ctx.activeLayerId,
            id = action.newShapeId,
            payload = action.newShapePayload,
            bounds = action.newShapeBounds,
            targetVersion = 1L
        )

        val startPt = action.sourceAnchor.resolveWorldPoint(src.bounds)
        val endPt = action.targetAnchor.resolveWorldPoint(action.newShapeBounds)

        val minX = min(startPt.x, endPt.x) - 4.0
        val minY = min(startPt.y, endPt.y) - 4.0
        val maxX = max(startPt.x, endPt.x) + 4.0
        val maxY = max(startPt.y, endPt.y) + 4.0
        val connectorBounds = DocRect(minX, minY, maxX, maxY)

        val resolvedConnectorPayload = ConnectorPayload(
            sourceId = action.sourceId,
            targetId = action.newShapeId,
            sourceAnchor = action.sourceAnchor,
            targetAnchor = action.targetAnchor,
            strokeColor = action.strokeColor,
            strokeWidthPt = action.strokeWidthPt,
            danglingPolicy = action.danglingPolicy,
            resolvedStartPoint = startPt,
            resolvedEndPoint = endPt
        )

        val connectorInsert = InsertObject(
            space = ctx.activeSpaceId,
            layer = ctx.activeLayerId,
            id = action.connectorId,
            payload = resolvedConnectorPayload,
            bounds = connectorBounds,
            targetVersion = 1L
        )

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = emptyList(),
                mutations = listOf(newShapeInsert, connectorInsert),
                gesture = ctx.gestureId,
                label = UndoLabel("Quick Diagram Create and Connect")
            )
        )
    }
}
