package inkspace.core.unit

import kotlinx.serialization.Serializable
import kotlin.math.abs

const val MAX_WORLD_COORDINATE_PT: Double = 1e9
const val MIN_CAMERA_SCALE: Double = 1e-4
const val MAX_CAMERA_SCALE: Double = 1e4

@Serializable
data class DocPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite: ($x, $y)" }
        require(abs(x) <= MAX_WORLD_COORDINATE_PT && abs(y) <= MAX_WORLD_COORDINATE_PT) {
            "Coordinates ($x, $y) exceed max world envelope ($MAX_WORLD_COORDINATE_PT pt)"
        }
    }

    operator fun plus(other: DocPoint): DocPoint = DocPoint(x + other.x, y + other.y)
    operator fun minus(other: DocPoint): DocPoint = DocPoint(x - other.x, y - other.y)
}

@Serializable
data class DocRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "DocRect coordinates must be finite: left=$left, top=$top, right=$right, bottom=$bottom"
        }
        require(left <= right) { "DocRect left must be <= right: $left > $right" }
        require(top <= bottom) { "DocRect top must be <= bottom: $top > $bottom" }
        require(
            abs(left) <= MAX_WORLD_COORDINATE_PT && abs(right) <= MAX_WORLD_COORDINATE_PT &&
            abs(top) <= MAX_WORLD_COORDINATE_PT && abs(bottom) <= MAX_WORLD_COORDINATE_PT
        ) {
            "DocRect exceeds max world envelope ($MAX_WORLD_COORDINATE_PT pt)"
        }
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun contains(point: DocPoint): Boolean {
        return point.x in left..right && point.y in top..bottom
    }

    fun intersects(other: DocRect): Boolean {
        return left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top
    }
}

@Serializable
@JvmInline
value class ViewPx(val value: Float) {
    init {
        require(value.isFinite()) { "ViewPx must be finite: $value" }
    }
}

@Serializable
@JvmInline
value class DocUnits(val points: Double) {
    init {
        require(points.isFinite()) { "DocUnits must be finite: $points" }
    }
}

@Serializable
data class ViewPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "ViewPoint coordinates must be finite: ($x, $y)" }
    }
}

@Serializable
data class CameraTransform(
    val scale: Double = 1.0,
    val translateX: Double = 0.0,
    val translateY: Double = 0.0
) {
    init {
        require(scale.isFinite() && translateX.isFinite() && translateY.isFinite()) {
            "CameraTransform values must be finite: scale=$scale, tx=$translateX, ty=$translateY"
        }
        require(scale in MIN_CAMERA_SCALE..MAX_CAMERA_SCALE) {
            "Camera scale $scale out of bounds [$MIN_CAMERA_SCALE, $MAX_CAMERA_SCALE]"
        }
    }

    /**
     * Converts a document coordinate (points) to viewport pixel coordinates.
     */
    fun docToView(doc: DocPoint): ViewPoint {
        val vx = (doc.x * scale) + translateX
        val vy = (doc.y * scale) + translateY
        return ViewPoint(vx, vy)
    }

    /**
     * Converts a viewport coordinate (pixels) to document coordinates (points).
     */
    fun viewToDoc(view: ViewPoint): DocPoint {
        val dx = (view.x - translateX) / scale
        val dy = (view.y - translateY) / scale
        return DocPoint(dx, dy)
    }
}
