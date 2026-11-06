package inkspace.core.payload

import inkspace.core.id.AssetId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ObjectPayload

@Serializable
@SerialName("stroke")
data class StrokePayload(
    val brushId: String,
    val color: Int,
    val baseWidthPt: Double,
    val origin: DocPoint,
    val samples: List<StrokeSamplePoint>,
    val smoothingVersion: Int = 1
) : ObjectPayload {
    init {
        require(baseWidthPt > 0.0 && baseWidthPt.isFinite()) { "baseWidthPt must be positive finite: $baseWidthPt" }
    }
}

@Serializable
data class StrokeSamplePoint(
    val relX: Float,
    val relY: Float,
    val pressure: Float = 0.5f,
    val tilt: Float = 0.0f,
    val timeDeltaMs: Int = 0
) {
    init {
        require(relX.isFinite() && relY.isFinite()) { "Relative sample coordinates must be finite: ($relX, $relY)" }
        require(pressure in 0.0f..1.0f) { "Pressure must be in [0, 1]: $pressure" }
    }
}

@Serializable
enum class ArrowheadStyle {
    NONE,
    OPEN_ARROW,
    CLOSED_TRIANGLE,
    DIAMOND,
    CIRCLE
}

@Serializable
@SerialName("shape")
data class ShapePayload(
    val shapeType: ShapeType,
    val strokeColor: Int,
    val fillColor: Int? = null,
    val strokeWidthPt: Double = 1.0,
    val cornerRadiusPt: Double = 0.0,
    val vertices: List<DocPoint> = emptyList(),
    val controlPoints: List<DocPoint> = emptyList(),
    val startArrowhead: ArrowheadStyle = ArrowheadStyle.NONE,
    val endArrowhead: ArrowheadStyle = if (shapeType == ShapeType.Arrow) ArrowheadStyle.OPEN_ARROW else ArrowheadStyle.NONE,
    val startAngleRad: Double = 0.0,
    val sweepAngleRad: Double = 0.0
) : ObjectPayload {
    val frozenVertices: List<DocPoint> = java.util.List.copyOf(vertices)
    val frozenControlPoints: List<DocPoint> = java.util.List.copyOf(controlPoints)

    init {
        require(strokeWidthPt > 0.0 && strokeWidthPt.isFinite()) { "strokeWidthPt must be positive finite" }
        require(cornerRadiusPt >= 0.0 && cornerRadiusPt.isFinite()) { "cornerRadiusPt must be non-negative finite" }
        require(startAngleRad.isFinite() && sweepAngleRad.isFinite()) { "Arc angles must be finite" }
    }
}

@Serializable
enum class ShapeType {
    Rectangle,
    RoundedRectangle,
    Square,
    Circle,
    Ellipse,
    Line,
    Arrow,
    Triangle,
    Polygon,
    Polyline,
    Curve,
    Arc
}

@Serializable
enum class AnchorSide {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
    CENTER,
    CUSTOM
}

@Serializable
data class ConnectorAnchor(
    val side: AnchorSide = AnchorSide.CENTER,
    val fraction: Double = 0.5,
    val offsetPt: DocPoint = DocPoint(0.0, 0.0)
) {
    init {
        require(fraction.isFinite() && fraction in 0.0..1.0) { "Anchor fraction must be in [0, 1]: $fraction" }
    }

    fun resolveWorldPoint(targetBounds: DocRect): DocPoint {
        val f = fraction.coerceIn(0.0, 1.0)
        val base = when (side) {
            AnchorSide.TOP -> DocPoint(targetBounds.left + targetBounds.width * f, targetBounds.top)
            AnchorSide.BOTTOM -> DocPoint(targetBounds.left + targetBounds.width * f, targetBounds.bottom)
            AnchorSide.LEFT -> DocPoint(targetBounds.left, targetBounds.top + targetBounds.height * f)
            AnchorSide.RIGHT -> DocPoint(targetBounds.right, targetBounds.top + targetBounds.height * f)
            AnchorSide.CENTER -> DocPoint(
                (targetBounds.left + targetBounds.right) * 0.5,
                (targetBounds.top + targetBounds.bottom) * 0.5
            )
            AnchorSide.CUSTOM -> DocPoint(
                (targetBounds.left + targetBounds.right) * 0.5 + offsetPt.x,
                (targetBounds.top + targetBounds.bottom) * 0.5 + offsetPt.y
            )
        }
        return if (side == AnchorSide.CUSTOM) {
            base
        } else {
            DocPoint(base.x + offsetPt.x, base.y + offsetPt.y)
        }
    }
}

@Serializable
enum class DanglingEndpointPolicy {
    DELETE_CONNECTOR,
    DETACH_TO_POINT
}

@Serializable
@SerialName("connector")
data class ConnectorPayload(
    val sourceId: ObjectId,
    val targetId: ObjectId,
    val strokeColor: Int,
    val strokeWidthPt: Double = 1.0,
    val isDirected: Boolean = true,
    val sourceAnchor: ConnectorAnchor = ConnectorAnchor(AnchorSide.RIGHT, 0.5),
    val targetAnchor: ConnectorAnchor = ConnectorAnchor(AnchorSide.LEFT, 0.5),
    val controlPoints: List<DocPoint> = emptyList(),
    val startArrowhead: ArrowheadStyle = ArrowheadStyle.NONE,
    val endArrowhead: ArrowheadStyle = if (isDirected) ArrowheadStyle.OPEN_ARROW else ArrowheadStyle.NONE,
    val danglingPolicy: DanglingEndpointPolicy = DanglingEndpointPolicy.DELETE_CONNECTOR,
    val resolvedStartPoint: DocPoint? = null,
    val resolvedEndPoint: DocPoint? = null
) : ObjectPayload {
    val frozenControlPoints: List<DocPoint> = java.util.List.copyOf(controlPoints)

    init {
        require(strokeWidthPt > 0.0 && strokeWidthPt.isFinite()) { "strokeWidthPt must be positive finite" }
    }
}

@Serializable
@SerialName("text")
data class TextPayload(
    val text: String,
    val fontSizePt: Double = 12.0,
    val fontColor: Int = 0xFF000000.toInt(),
    val isRtl: Boolean = false,
    val runs: List<TextRun> = emptyList()
) : ObjectPayload {
    init {
        require(fontSizePt > 0.0 && fontSizePt.isFinite()) { "fontSizePt must be positive finite" }
    }
}

@Serializable
data class TextRun(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int? = null
)

@Serializable
@SerialName("image")
data class ImagePayload(
    val asset: AssetId,
    val naturalWidthPx: Int,
    val naturalHeightPx: Int,
    val cropRect: DocRect? = null
) : ObjectPayload {
    init {
        require(naturalWidthPx > 0 && naturalHeightPx > 0) { "Natural dimensions must be positive" }
    }
}

@Serializable
@SerialName("group")
data class GroupPayload(
    val members: List<ObjectId>
) : ObjectPayload {
    init {
        require(members.isNotEmpty()) { "Group cannot be empty" }
        require(members.distinct().size == members.size) { "Group cannot contain duplicate members" }
    }
}

@Serializable
@SerialName("sticky")
data class StickyPayload(
    val color: Int,
    val content: String,
    val isCollapsed: Boolean = false
) : ObjectPayload

@Serializable
@SerialName("tape")
data class TapePayload(
    val patternColor: Int,
    val opacity: Float = 0.8f
) : ObjectPayload {
    init {
        require(opacity in 0.0f..1.0f) { "Opacity must be in [0, 1]" }
    }
}

@Serializable
@SerialName("link_anchor")
data class LinkAnchorPayload(
    val title: String,
    val targetPage: PageId? = null,
    val targetUrl: String? = null
) : ObjectPayload
