package inkspace.objects

import inkspace.core.model.SceneObject
import inkspace.core.objects.SelectionRule
import inkspace.core.outcome.Outcome
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.GeometryRequest
import inkspace.core.read.DocumentRead
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Two-stage spatial query (`DocumentRead.queryBounds`) followed by precise geometric hit-testing
 * for strokes, shapes, text boxes, images, connectors, and groups.
 */
object HitTest {

    fun extractWorldPoints(
        obj: SceneObject,
        read: DocumentRead,
        geometryPort: GeometryPort? = null
    ): List<DocPoint> {
        val payload = obj.payload
        if (payload is GroupPayload) {
            val all = ArrayList<DocPoint>()
            for (memberId in payload.members) {
                val member = read.getObject(memberId) ?: continue
                all.addAll(extractWorldPoints(member, read, geometryPort))
            }
            if (all.isNotEmpty()) return all
        }

        if (payload is StrokePayload) {
            val rawSamples = payload.samples
            if (rawSamples.isEmpty()) {
                return listOf(obj.transform.transformPoint(payload.origin))
            }
            val basePoints = if (geometryPort != null && rawSamples.size <= 512) {
                val relSamples = ArrayList<DocPoint>(rawSamples.size)
                for (s in rawSamples) {
                    relSamples.add(DocPoint(s.relX.toDouble(), s.relY.toDouble()))
                }
                val req = GeometryRequest(
                    strokeId = obj.id,
                    origin = payload.origin,
                    samples = relSamples,
                    baseWidthPt = payload.baseWidthPt,
                    brushId = payload.brushId,
                    smoothingVersion = payload.smoothingVersion
                )
                when (val out = geometryPort.paths(req)) {
                    is Outcome.Success -> {
                        val art = out.value
                        if (art.relativePoints.isNotEmpty()) {
                            art.relativePoints.map { rp ->
                                obj.transform.transformPoint(DocPoint(art.origin.x + rp.x, art.origin.y + rp.y))
                            }
                        } else {
                            relSamples.map {
                                obj.transform.transformPoint(DocPoint(payload.origin.x + it.x, payload.origin.y + it.y))
                            }
                        }
                    }
                    is Outcome.Failure -> relSamples.map {
                        obj.transform.transformPoint(DocPoint(payload.origin.x + it.x, payload.origin.y + it.y))
                    }
                }
            } else {
                val list = ArrayList<DocPoint>(rawSamples.size)
                for (s in rawSamples) {
                    val localPt = DocPoint(payload.origin.x + s.relX, payload.origin.y + s.relY)
                    list.add(obj.transform.transformPoint(localPt))
                }
                list
            }
            return basePoints
        }

        // Non-stroke objects: use the 4 corners + 4 edge midpoints + center of their world bounds
        val b = obj.bounds
        val cx = (b.left + b.right) * 0.5
        val cy = (b.top + b.bottom) * 0.5
        return listOf(
            DocPoint(b.left, b.top),
            DocPoint(b.right, b.top),
            DocPoint(b.right, b.bottom),
            DocPoint(b.left, b.bottom),
            DocPoint(cx, b.top),
            DocPoint(b.right, cy),
            DocPoint(cx, b.bottom),
            DocPoint(b.left, cy),
            DocPoint(cx, cy)
        )
    }

    fun matchesRectRule(
        obj: SceneObject,
        rect: DocRect,
        rule: SelectionRule,
        read: DocumentRead,
        geometryPort: GeometryPort? = null
    ): Boolean {
        val b = obj.bounds
        if (!b.intersects(rect)) return false
        if (b.left >= rect.left && b.right <= rect.right && b.top >= rect.top && b.bottom <= rect.bottom) {
            return true
        }

        val points = extractWorldPoints(obj, read, geometryPort)
        if (points.isEmpty()) return false

        return when (rule) {
            SelectionRule.CONTAINS -> {
                points.all { rect.contains(it) }
            }
            SelectionRule.INTERSECTS -> {
                if (points.any { rect.contains(it) }) return true
                for (i in 0 until points.size - 1) {
                    if (segmentIntersectsRect(points[i], points[i + 1], rect)) {
                        return true
                    }
                }
                if (obj.payload !is StrokePayload && obj.bounds.contains(DocPoint(rect.left, rect.top))) {
                    return true
                }
                false
            }
        }
    }

    fun matchesPolygonRule(
        obj: SceneObject,
        polygon: List<DocPoint>,
        polygonBounds: DocRect,
        rule: SelectionRule,
        read: DocumentRead,
        geometryPort: GeometryPort? = null
    ): Boolean {
        if (polygon.size < 3) return false
        if (!obj.bounds.intersects(polygonBounds)) return false

        // Fast-path for INTERSECTS on StrokePayload: check first sample before allocating full point list
        if (rule == SelectionRule.INTERSECTS && geometryPort == null) {
            val sp = obj.payload as? StrokePayload
            if (sp != null && sp.samples.isNotEmpty()) {
                val s0 = sp.samples[0]
                val firstWorld = obj.transform.transformPoint(
                    DocPoint(sp.origin.x + s0.relX, sp.origin.y + s0.relY)
                )
                if (pointInPolygon(firstWorld, polygon)) {
                    return true
                }
            }
        }

        val points = extractWorldPoints(obj, read, geometryPort)
        if (points.isEmpty()) return false

        return when (rule) {
            SelectionRule.CONTAINS -> {
                points.all { pointInPolygon(it, polygon) }
            }
            SelectionRule.INTERSECTS -> {
                if (points.any { pointInPolygon(it, polygon) }) return true
                for (i in 0 until points.size - 1) {
                    if (segmentIntersectsPolygon(points[i], points[i + 1], polygon)) {
                        return true
                    }
                }
                if (obj.payload !is StrokePayload && obj.bounds.contains(polygon.first())) {
                    return true
                }
                false
            }
        }
    }

    fun pointInPolygon(pt: DocPoint, polygon: List<DocPoint>): Boolean {
        var inside = false
        val n = polygon.size
        var j = n - 1
        for (i in 0 until n) {
            val pi = polygon[i]
            val pj = polygon[j]
            if (distancePointToSegmentSq(pt, pi, pj) <= 1e-6) {
                return true
            }
            val intersectsRay = ((pi.y > pt.y) != (pj.y > pt.y)) &&
                (pt.x < (pj.x - pi.x) * (pt.y - pi.y) / (pj.y - pi.y) + pi.x)
            if (intersectsRay) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    fun segmentIntersectsPolygon(p1: DocPoint, p2: DocPoint, polygon: List<DocPoint>): Boolean {
        if (pointInPolygon(p1, polygon) || pointInPolygon(p2, polygon)) return true
        val n = polygon.size
        for (i in 0 until n) {
            val a = polygon[i]
            val b = polygon[(i + 1) % n]
            if (segmentIntersectsSegment(p1, p2, a, b)) {
                return true
            }
        }
        return false
    }

    fun segmentIntersectsRect(p1: DocPoint, p2: DocPoint, rect: DocRect): Boolean {
        if (rect.contains(p1) || rect.contains(p2)) return true
        val c0 = DocPoint(rect.left, rect.top)
        val c1 = DocPoint(rect.right, rect.top)
        val c2 = DocPoint(rect.right, rect.bottom)
        val c3 = DocPoint(rect.left, rect.bottom)
        return segmentIntersectsSegment(p1, p2, c0, c1) ||
            segmentIntersectsSegment(p1, p2, c1, c2) ||
            segmentIntersectsSegment(p1, p2, c2, c3) ||
            segmentIntersectsSegment(p1, p2, c3, c0)
    }

    fun segmentIntersectsSegment(a1: DocPoint, a2: DocPoint, b1: DocPoint, b2: DocPoint): Boolean {
        val d1 = crossProductSign(b1, b2, a1)
        val d2 = crossProductSign(b1, b2, a2)
        val d3 = crossProductSign(a1, a2, b1)
        val d4 = crossProductSign(a1, a2, b2)

        if (((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0)) &&
            ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0))
        ) {
            return true
        }
        if (abs(d1) <= 1e-9 && onSegmentProjection(b1, b2, a1)) return true
        if (abs(d2) <= 1e-9 && onSegmentProjection(b1, b2, a2)) return true
        if (abs(d3) <= 1e-9 && onSegmentProjection(a1, a2, b1)) return true
        if (abs(d4) <= 1e-9 && onSegmentProjection(a1, a2, b2)) return true
        return false
    }

    fun distancePointToSegmentSq(pt: DocPoint, a: DocPoint, b: DocPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-12) {
            val px = pt.x - a.x
            val py = pt.y - a.y
            return px * px + py * py
        }
        val t = (((pt.x - a.x) * dx + (pt.y - a.y) * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        val ex = pt.x - projX
        val ey = pt.y - projY
        return ex * ex + ey * ey
    }

    private fun crossProductSign(a: DocPoint, b: DocPoint, c: DocPoint): Double {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }

    private fun onSegmentProjection(a: DocPoint, b: DocPoint, p: DocPoint): Boolean {
        return p.x >= min(a.x, b.x) - 1e-9 &&
            p.x <= max(a.x, b.x) + 1e-9 &&
            p.y >= min(a.y, b.y) - 1e-9 &&
            p.y <= max(a.y, b.y) + 1e-9
    }
}
