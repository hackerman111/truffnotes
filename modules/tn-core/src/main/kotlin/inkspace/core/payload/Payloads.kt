@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
    val sweepAngleRad: Double = 0.0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val label: TextPayload? = null
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
    val runs: List<TextRun> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val paragraph: inkspace.core.text.ParagraphStyle = inkspace.core.text.ParagraphStyle(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fontFamily: String = "sans-serif",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fontAsset: AssetId? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val pageBound: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val containerBounds: DocRect? = null
) : ObjectPayload {
    init {
        require(fontSizePt > 0.0 && fontSizePt.isFinite() && fontSizePt <= 10000.0) { "fontSizePt must be positive finite and bounded" }
        require(text.length <= 10000000 && runs.size <= 100000 && fontFamily.length <= 256)
        require(runs.all { it.start >= 0 && it.end >= it.start && it.end <= text.length })
    }
}

@Serializable
data class TextRun(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val strike: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val highlight: Int? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fontFamily: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fontSizePt: Double? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fontAsset: AssetId? = null
) {
    init { require(start >= 0 && end >= start && (fontSizePt == null || fontSizePt.isFinite() && fontSizePt in 0.1..10000.0) && (fontFamily?.length ?: 0) <= 256) }
}

@Serializable
@SerialName("image")
data class ImagePayload(
    val asset: AssetId,
    val naturalWidthPx: Int,
    val naturalHeightPx: Int,
    val cropRect: DocRect? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val cropMask: List<DocPoint> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val exifOrientation: Int = 1,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val posterFrame: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val containerBounds: DocRect? = null
) : ObjectPayload {
    init {
        require(naturalWidthPx > 0 && naturalHeightPx > 0 && naturalWidthPx.toLong()*naturalHeightPx <= 1000000000L) { "Natural dimensions exceed budget" }
        require(exifOrientation in 1..8 && posterFrame in 0..119 && cropMask.size <= 10000)
        require(cropMask.isEmpty() || cropMask.size >= 3)
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
    val isCollapsed: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val richText: TextPayload? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val attachedIds: List<ObjectId> = emptyList()
) : ObjectPayload {
    init { require(content.length <= 1000000 && attachedIds.size <= 10000 && attachedIds.distinct().size == attachedIds.size) }
}

@Serializable
@SerialName("tape")
data class TapePayload(
    val patternColor: Int,
    val opacity: Float = 0.8f,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val points: List<DocPoint> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val widthPt: Double = 24.0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val pattern: String = "solid",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val straight: Boolean = false
) : ObjectPayload {
    init {
        require(opacity in 0.0f..1.0f) { "Opacity must be in [0, 1]" }
        require(points.size <= 64000 && widthPt.isFinite() && widthPt in 0.1..1000.0 && pattern in listOf("solid","stripe","dots","grid","diagonal"))
    }
}

@Serializable
@SerialName("link_anchor")
data class LinkAnchorPayload(
    val title: String,
    val targetPage: PageId? = null,
    val targetUrl: String? = null
) : ObjectPayload

@Serializable
@SerialName("pdf-page")
data class PdfObjectPayload(val page: inkspace.core.pdf.PdfPageRef, val localBounds: DocRect? = null) : ObjectPayload {
    init { require(page.pageIndex in 0..99999 && page.cropBox.width>0 && page.cropBox.height>0 && page.rotation in listOf(0,90,180,270))
        require(localBounds==null || localBounds.width>0 && localBounds.height>0) }
}
