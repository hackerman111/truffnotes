package inkspace.render

import inkspace.core.payload.*
import inkspace.core.unit.*
import kotlin.math.*

/** Shared sampled contours: every replay target consumes these same points. */
internal data class ShapeContour(val points: List<DocPoint>, val closed: Boolean = false, val fill: Boolean = false)
internal object ShapeReplayGeometry {
    fun contours(shape: ShapePayload, bounds: DocRect): List<ShapeContour> {
        val vertices = shape.frozenVertices
        val l = bounds.left; val r = bounds.right; val t = bounds.top; val b = bounds.bottom
        val cx = (l+r)/2; val cy = (t+b)/2
        fun ellipse(rx: Double, ry: Double, start: Double = 0.0, sweep: Double = 2*PI) = (0..96).map { i -> val a = start+sweep*i/96; DocPoint(cx+rx*cos(a),cy+ry*sin(a)) }
        val points = when(shape.shapeType) {
            ShapeType.Circle -> ellipse(min(bounds.width,bounds.height)/2,min(bounds.width,bounds.height)/2)
            ShapeType.Ellipse -> ellipse(bounds.width/2,bounds.height/2)
            ShapeType.Arc -> ellipse(bounds.width/2,bounds.height/2,shape.startAngleRad,shape.sweepAngleRad.takeUnless { it == 0.0 } ?: PI)
            ShapeType.Line, ShapeType.Arrow -> vertices.takeIf { it.size >= 2 }?.let { listOf(it.first(),it.last()) } ?: listOf(DocPoint(l,t),DocPoint(r,b))
            ShapeType.Curve -> {
                val start = vertices.firstOrNull() ?: DocPoint(l,b); val end = vertices.lastOrNull() ?: DocPoint(r,t)
                val controls = shape.frozenControlPoints
                (0..64).map { i -> val u = i/64.0; val v = 1-u
                    when {
                        controls.size >= 2 -> DocPoint(v*v*v*start.x+3*v*v*u*controls[0].x+3*v*u*u*controls[1].x+u*u*u*end.x,v*v*v*start.y+3*v*v*u*controls[0].y+3*v*u*u*controls[1].y+u*u*u*end.y)
                        controls.size == 1 -> DocPoint(v*v*start.x+2*v*u*controls[0].x+u*u*end.x,v*v*start.y+2*v*u*controls[0].y+u*u*end.y)
                        else -> DocPoint(v*start.x+u*end.x,v*start.y+u*end.y)
                    }
                }
            }
            ShapeType.Triangle -> vertices.takeIf { it.size >= 3 } ?: listOf(DocPoint(cx,t),DocPoint(r,b),DocPoint(l,b))
            ShapeType.Polygon, ShapeType.Polyline -> vertices
            ShapeType.RoundedRectangle -> {
                val radius = shape.cornerRadiusPt.coerceAtMost(min(bounds.width,bounds.height)/2)
                if (radius == 0.0) listOf(DocPoint(l,t),DocPoint(r,t),DocPoint(r,b),DocPoint(l,b)) else buildList {
                    listOf(Triple(r-radius,t+radius,-PI/2),Triple(r-radius,b-radius,0.0),Triple(l+radius,b-radius,PI/2),Triple(l+radius,t+radius,PI)).forEach { (x,y,start) ->
                        (0..12).forEach { i -> val a = start + PI/2*i/12; add(DocPoint(x+radius*cos(a),y+radius*sin(a))) }
                    }
                }
            }
            ShapeType.Square -> { val side = min(bounds.width,bounds.height); listOf(DocPoint(l,t),DocPoint(l+side,t),DocPoint(l+side,t+side),DocPoint(l,t+side)) }
            ShapeType.Rectangle -> vertices.takeIf { it.size >= 4 } ?: listOf(DocPoint(l,t),DocPoint(r,t),DocPoint(r,b),DocPoint(l,b))
        }
        val closed = shape.shapeType !in setOf(ShapeType.Line,ShapeType.Arrow,ShapeType.Polyline,ShapeType.Curve,ShapeType.Arc)
        val result = mutableListOf(ShapeContour(points,closed,closed && shape.fillColor != null))
        if (!closed && points.size >= 2) {
            fun head(tip: DocPoint, previous: DocPoint, style: ArrowheadStyle) {
                if (style == ArrowheadStyle.NONE) return
                val length = max(6.0,shape.strokeWidthPt*4)
                val a = atan2(tip.y-previous.y,tip.x-previous.x)
                fun p(back: Double, side: Double) = DocPoint(tip.x-back*cos(a)-side*sin(a),tip.y-back*sin(a)+side*cos(a))
                result += when(style) {
                    ArrowheadStyle.OPEN_ARROW -> ShapeContour(listOf(p(length,length*.45),tip,p(length,-length*.45)))
                    ArrowheadStyle.CLOSED_TRIANGLE -> ShapeContour(listOf(tip,p(length,length*.45),p(length,-length*.45)),true,true)
                    ArrowheadStyle.DIAMOND -> ShapeContour(listOf(tip,p(length*.5,length*.4),p(length,0.0),p(length*.5,-length*.4)),true,true)
                    ArrowheadStyle.CIRCLE -> ShapeContour((0..32).map { i -> val angle = i*2*PI/32; val center = p(length*.5,0.0); DocPoint(center.x+length*.5*cos(angle),center.y+length*.5*sin(angle)) },true,true)
                    ArrowheadStyle.NONE -> return
                }
            }
            head(points.first(),points[1],shape.startArrowhead); head(points.last(),points[points.lastIndex-1],shape.endArrowhead)
        }
        return result
    }
}
