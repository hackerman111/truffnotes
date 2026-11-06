package inkspace.core.objects

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.SpaceId
import inkspace.core.ink.BrushKind
import inkspace.core.limits.Limits
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.SceneObject
import inkspace.core.payload.AnchorSide
import inkspace.core.payload.ArrowheadStyle
import inkspace.core.payload.ConnectorAnchor
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.DanglingEndpointPolicy
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.LinkAnchorPayload
import inkspace.core.payload.ObjectPayload
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StickyPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.payload.TapePayload
import inkspace.core.payload.TextPayload
import inkspace.core.ports.GeometryPort
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable

@Serializable
enum class SelectionMode {
    RECTANGLE,
    FREEHAND
}

@Serializable
enum class SelectionRule {
    CONTAINS,
    INTERSECTS
}

@Serializable
enum class SelectionCombinator {
    REPLACE,
    ADD,
    SUBTRACT
}

@Serializable
enum class ObjectKindFilter {
    STROKE,
    SHAPE,
    CONNECTOR,
    TEXT,
    IMAGE,
    GROUP,
    STICKY,
    TAPE,
    LINK_ANCHOR;

    companion object {
        val ALL: Set<ObjectKindFilter> = java.util.Set.copyOf(entries)

        fun fromPayload(payload: ObjectPayload): ObjectKindFilter {
            return when (payload) {
                is StrokePayload -> STROKE
                is ShapePayload -> SHAPE
                is ConnectorPayload -> CONNECTOR
                is TextPayload -> TEXT
                is ImagePayload -> IMAGE
                is GroupPayload -> GROUP
                is StickyPayload -> STICKY
                is TapePayload -> TAPE
                is LinkAnchorPayload -> LINK_ANCHOR
                is inkspace.core.payload.PdfObjectPayload -> IMAGE
            }
        }
    }
}

@Serializable
data class SelectionFilter(
    val activeLayerOnly: Boolean = false,
    val activeLayerId: LayerId? = null,
    val allowedKinds: Set<ObjectKindFilter> = ObjectKindFilter.ALL
) {
    val frozenAllowedKinds: Set<ObjectKindFilter> = java.util.Set.copyOf(allowedKinds)
}

@Serializable
data class SelectionQuery(
    val spaceId: SpaceId = SpaceId("space-1"),
    val mode: SelectionMode = SelectionMode.RECTANGLE,
    val rule: SelectionRule = SelectionRule.INTERSECTS,
    val combinator: SelectionCombinator = SelectionCombinator.REPLACE,
    val rect: DocRect? = null,
    val polygonPoints: List<DocPoint> = emptyList(),
    val filter: SelectionFilter = SelectionFilter(),
    val enteredGroupId: ObjectId? = null
) {
    val frozenPolygonPoints: List<DocPoint> = java.util.List.copyOf(polygonPoints)
}

@Serializable
data class SelectionSet(
    val selectedIds: Set<ObjectId> = emptySet(),
    val enteredGroupId: ObjectId? = null,
    val bounds: DocRect? = null
) {
    val frozenSelectedIds: Set<ObjectId> = java.util.Set.copyOf(selectedIds)

    val isEmpty: Boolean get() = frozenSelectedIds.isEmpty()
    val size: Int get() = frozenSelectedIds.size
}

@Serializable
enum class FlipAxis {
    HORIZONTAL,
    VERTICAL
}

@Serializable
enum class ZOrderCommand {
    BRING_TO_FRONT,
    SEND_TO_BACK,
    BRING_FORWARD,
    SEND_BACKWARD
}

@Serializable
data class StylePatch(
    val colorArgb: Int? = null,
    val strokeWidthPt: Double? = null,
    val opacity: Float? = null
) {
    init {
        if (strokeWidthPt != null) {
            require(strokeWidthPt.isFinite() && strokeWidthPt in 0.1..200.0) {
                "strokeWidthPt must be in [0.1, 200.0]: $strokeWidthPt"
            }
        }
        if (opacity != null) {
            require(opacity.isFinite() && opacity in 0.0f..1.0f) {
                "opacity must be in [0, 1]: $opacity"
            }
        }
    }
}

@Serializable
enum class EraserMode {
    PRECISION,
    SEGMENT,
    STROKE,
    OBJECT
}

@Serializable
enum class EraserShape {
    CIRCLE,
    SQUARE
}

@Serializable
enum class EraserLayerPolicy {
    ACTIVE_LAYER_ONLY,
    VISIBLE_UNLOCKED_LAYERS
}

@Serializable
data class EraserSpec(
    val mode: EraserMode = EraserMode.SEGMENT,
    val shape: EraserShape = EraserShape.CIRCLE,
    val radiusPt: Double = 6.0,
    val layerPolicy: EraserLayerPolicy = EraserLayerPolicy.VISIBLE_UNLOCKED_LAYERS,
    val allowedObjectKinds: Set<ObjectKindFilter> = ObjectKindFilter.ALL
) {
    val frozenAllowedKinds: Set<ObjectKindFilter> = java.util.Set.copyOf(allowedObjectKinds)

    init {
        require(radiusPt.isFinite() && radiusPt in 0.1..500.0) {
            "Eraser radiusPt must be in [0.1, 500.0]: $radiusPt"
        }
    }
}

@Serializable
@JvmInline
value class TransferId(val value: String) {
    init {
        require(value.isNotBlank()) { "TransferId cannot be blank" }
    }
}

@Serializable
data class ClipboardObjectEntry(
    val originalId: ObjectId,
    val objectSnapshot: SceneObject,
    val parentGroupId: ObjectId? = null
) {
    val frozenObject: SceneObject = objectSnapshot.frozen()
}

@Serializable
data class ClipboardBundle(
    val transferId: TransferId,
    val sourceDocument: DocumentId,
    val sourceSpace: SpaceId,
    val sourceBounds: DocRect,
    val entries: List<ClipboardObjectEntry>
) {
    val frozenEntries: List<ClipboardObjectEntry> = java.util.List.copyOf(entries)

    init {
        require(entries.isNotEmpty()) { "ClipboardBundle entries cannot be empty" }
    }
}

@Serializable
data class PreviewTransformOverlay(
    val targetIds: Set<ObjectId>,
    val deltaTransform: AffineTransform2D,
    val previewBounds: DocRect
) {
    val frozenTargetIds: Set<ObjectId> = java.util.Set.copyOf(targetIds)
}

@Serializable
data class StrokeData(
    val origin: DocPoint,
    val samples: List<StrokeSamplePoint>,
    val brushKind: BrushKind = BrushKind.BALL,
    val baseWidthPt: Double = 2.0,
    val colorArgb: Int = 0xFF000000.toInt(),
    val holdDurationMs: Long = 0L,
    val isClosedLoop: Boolean = false
) {
    val frozenSamples: List<StrokeSamplePoint> = java.util.List.copyOf(samples)
    val points: List<DocPoint>
        get() = samples.map { DocPoint(origin.x + it.relX, origin.y + it.relY) }

    init {
        require(baseWidthPt > 0.0 && baseWidthPt.isFinite()) { "baseWidthPt must be positive finite" }
        require(holdDurationMs >= 0L) { "holdDurationMs must be non-negative" }
    }
}

@Serializable
data class RecognitionConfig(
    val drawAndHoldEnabled: Boolean = true,
    val scribbleToEraseEnabled: Boolean = true,
    val circleToLassoEnabled: Boolean = true,
    val snapToOtherShapesEnabled: Boolean = true,
    val ignoreHoldRequirement: Boolean = false,
    val holdThresholdMs: Long = 350L,
    val circleReholdThresholdMs: Long = 300L,
    val circleReholdWindowMs: Long = 1500L,
    val circleReholdLineTolerancePt: Double = 14.0,
    val sensitivity: Float = 0.5f,
    val maxLineFitRmsRatio: Double = 0.045,
    val closureDistanceRatio: Double = 0.22,
    val minShapeExtentPt: Double = 12.0,
    val scribbleMinReversals: Int = 3,
    val snapToShapeTolerancePt: Double = 12.0
) {
    init {
        require(holdThresholdMs >= 0L) { "holdThresholdMs must be non-negative" }
        require(circleReholdThresholdMs >= 0L) { "circleReholdThresholdMs must be non-negative" }
        require(circleReholdWindowMs >= 0L) { "circleReholdWindowMs must be non-negative" }
        require(circleReholdLineTolerancePt > 0.0 && circleReholdLineTolerancePt.isFinite()) {
            "circleReholdLineTolerancePt must be positive finite"
        }
        require(sensitivity in 0.0f..1.0f) { "sensitivity must be in [0, 1]" }
        require(maxLineFitRmsRatio > 0.0 && maxLineFitRmsRatio.isFinite()) {
            "maxLineFitRmsRatio must be positive finite"
        }
        require(closureDistanceRatio > 0.0 && closureDistanceRatio.isFinite()) {
            "closureDistanceRatio must be positive finite"
        }
        require(minShapeExtentPt > 0.0 && minShapeExtentPt.isFinite()) {
            "minShapeExtentPt must be positive finite"
        }
        require(scribbleMinReversals >= 2) { "scribbleMinReversals must be >= 2" }
        require(snapToShapeTolerancePt >= 0.0 && snapToShapeTolerancePt.isFinite()) {
            "snapToShapeTolerancePt must be non-negative finite"
        }
    }

    companion object {
        fun defaults(): RecognitionConfig = RecognitionConfig()
    }
}

@Serializable
sealed interface ShapeCandidate {
    @Serializable
    data class Recognized(
        val shapeType: ShapeType,
        val payload: ShapePayload,
        val bounds: DocRect,
        val confidence: Double,
        val snappedToTargetId: ObjectId? = null
    ) : ShapeCandidate {
        init {
            require(confidence.isFinite() && confidence in 0.0..1.0) { "Confidence must be in [0, 1]" }
        }
    }

    @Serializable
    data class AmbiguousKeepOriginal(
        val reason: String
    ) : ShapeCandidate

    @Serializable
    data class Rejected(
        val reason: String
    ) : ShapeCandidate
}

@Serializable
enum class GridKind {
    BLANK,
    RULED,
    SQUARED,
    DOTTED,
    MILLIMETER,
    ISOMETRIC,
    CUSTOM
}

@Serializable
data class GridSpec(
    val kind: GridKind = GridKind.BLANK,
    val spacingPt: Double = 24.0,
    val secondarySpacingPt: Double = 24.0,
    val colorArgb: Int = 0xFFCBD5E1.toInt(),
    val lineWidthPt: Double = 0.5,
    val isVisible: Boolean = true,
    val snapEnabled: Boolean = false,
    val snapTolerancePt: Double = 6.0
) {
    init {
        require(spacingPt > 0.0 && spacingPt.isFinite()) { "spacingPt must be positive finite" }
        require(secondarySpacingPt > 0.0 && secondarySpacingPt.isFinite()) {
            "secondarySpacingPt must be positive finite"
        }
        require(lineWidthPt > 0.0 && lineWidthPt.isFinite()) { "lineWidthPt must be positive finite" }
        require(snapTolerancePt >= 0.0 && snapTolerancePt.isFinite()) { "snapTolerancePt must be non-negative finite" }
    }
}

@Serializable
enum class RulerUnit {
    CENTIMETERS,
    INCHES
}

@Serializable
enum class RulerEdge {
    TOP_EDGE,
    BOTTOM_EDGE
}

@Serializable
data class RulerOverlayState(
    val isVisible: Boolean = false,
    val centerDoc: DocPoint = DocPoint(200.0, 200.0),
    val angleDegrees: Double = 0.0,
    val lengthPt: Double = 400.0,
    val thicknessPt: Double = 40.0,
    val unit: RulerUnit = RulerUnit.CENTIMETERS,
    val hideDigits: Boolean = false,
    val snapTolerancePt: Double = 18.0
) {
    init {
        require(angleDegrees.isFinite()) { "angleDegrees must be finite" }
        require(lengthPt > 0.0 && lengthPt.isFinite()) { "lengthPt must be positive finite" }
        require(thicknessPt > 0.0 && thicknessPt.isFinite()) { "thicknessPt must be positive finite" }
        require(snapTolerancePt >= 0.0 && snapTolerancePt.isFinite()) { "snapTolerancePt must be non-negative finite" }
    }
}

@Serializable
data class ProtractorOverlayState(
    val isVisible: Boolean = false,
    val centerDoc: DocPoint = DocPoint(200.0, 200.0),
    val radiusPt: Double = 90.0,
    val baseAngleDegrees: Double = 0.0,
    val startRayDegrees: Double = 0.0,
    val endRayDegrees: Double = 90.0,
    val snapTolerancePt: Double = 18.0,
    val angleSnapStepDegrees: Double = 15.0
) {
    init {
        require(radiusPt > 0.0 && radiusPt.isFinite()) { "radiusPt must be positive finite" }
        require(baseAngleDegrees.isFinite() && startRayDegrees.isFinite() && endRayDegrees.isFinite()) {
            "Protractor angles must be finite"
        }
        require(snapTolerancePt >= 0.0 && snapTolerancePt.isFinite()) { "snapTolerancePt must be non-negative finite" }
        require(angleSnapStepDegrees > 0.0 && angleSnapStepDegrees.isFinite()) {
            "angleSnapStepDegrees must be positive finite"
        }
    }
}

@Serializable
enum class GuideOrientation {
    HORIZONTAL,
    VERTICAL,
    ANGLED
}

@Serializable
data class GuideSpec(
    val id: String,
    val orientation: GuideOrientation,
    val positionPt: DocPoint,
    val angleDegrees: Double = when (orientation) {
        GuideOrientation.HORIZONTAL -> 0.0
        GuideOrientation.VERTICAL -> 90.0
        GuideOrientation.ANGLED -> 45.0
    },
    val snapTolerancePt: Double = 8.0
) {
    init {
        require(id.isNotBlank()) { "GuideSpec id cannot be blank" }
        require(angleDegrees.isFinite()) { "GuideSpec angleDegrees must be finite" }
        require(snapTolerancePt >= 0.0 && snapTolerancePt.isFinite()) { "snapTolerancePt must be non-negative finite" }
    }
}

@Serializable
enum class AlignmentGuideKind {
    CENTER_X,
    CENTER_Y,
    LEFT_EDGE,
    RIGHT_EDGE,
    TOP_EDGE,
    BOTTOM_EDGE
}

@Serializable
data class AlignmentGuideLine(
    val kind: AlignmentGuideKind,
    val coordinatePt: Double,
    val isDashedCenterLine: Boolean,
    val referenceObjectId: ObjectId
) {
    init {
        require(coordinatePt.isFinite()) { "AlignmentGuideLine coordinatePt must be finite" }
    }
}

@Serializable
data class SnapConfig(
    val grid: GridSpec = GridSpec(),
    val ruler: RulerOverlayState = RulerOverlayState(),
    val protractor: ProtractorOverlayState = ProtractorOverlayState(),
    val guides: List<GuideSpec> = emptyList(),
    val angleSnapEnabled: Boolean = true,
    val angleSnapStepDegrees: Double = 15.0,
    val angleSnapToleranceDegrees: Double = 4.0,
    val alignObjectsEnabled: Boolean = true,
    val alignObjectsTolerancePt: Double = 6.0
) {
    val frozenGuides: List<GuideSpec> = java.util.List.copyOf(guides)

    init {
        require(angleSnapStepDegrees > 0.0 && angleSnapStepDegrees.isFinite()) {
            "angleSnapStepDegrees must be positive finite"
        }
        require(angleSnapToleranceDegrees >= 0.0 && angleSnapToleranceDegrees.isFinite()) {
            "angleSnapToleranceDegrees must be non-negative finite"
        }
        require(alignObjectsTolerancePt >= 0.0 && alignObjectsTolerancePt.isFinite()) {
            "alignObjectsTolerancePt must be non-negative finite"
        }
    }
}

@Serializable
data class SnappedPointResult(
    val point: DocPoint,
    val snappedToRulerEdge: RulerEdge? = null,
    val snappedToProtractorArc: Boolean = false,
    val snappedToGuideId: String? = null,
    val snappedToGrid: Boolean = false,
    val rulerMeasurementUnits: Double? = null
)

@Serializable
data class ObjectAlignmentResult(
    val snappedBounds: DocRect,
    val deltaX: Double,
    val deltaY: Double,
    val activeGuides: List<AlignmentGuideLine> = emptyList()
) {
    val frozenActiveGuides: List<AlignmentGuideLine> = java.util.List.copyOf(activeGuides)
}

data class PlanContext(
    val activeSpaceId: SpaceId = SpaceId("space-1"),
    val activePageId: PageId = PageId("page-1"),
    val activeLayerId: LayerId = LayerId("layer-1"),
    val enteredGroupId: ObjectId? = null,
    val gestureId: GestureId = GestureId("gesture-obj-1"),
    val geometryPort: GeometryPort? = null,
    val limits: Limits = Limits(),
    val recognitionConfig: RecognitionConfig = RecognitionConfig(),
    val snapConfig: SnapConfig = SnapConfig()
)

sealed interface ObjectAction {
    data class Move(
        val targets: Set<ObjectId>,
        val deltaX: Double,
        val deltaY: Double
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class Scale(
        val targets: Set<ObjectId>,
        val scaleX: Double,
        val scaleY: Double,
        val pivot: DocPoint? = null
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class Rotate(
        val targets: Set<ObjectId>,
        val angleRadians: Double,
        val pivot: DocPoint? = null
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class Flip(
        val targets: Set<ObjectId>,
        val axis: FlipAxis,
        val pivot: DocPoint? = null
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class ApplyStyle(
        val targets: Set<ObjectId>,
        val patch: StylePatch
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class Delete(
        val targets: Set<ObjectId>
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class Duplicate(
        val targets: Set<ObjectId>,
        val offsetX: Double = 12.0,
        val offsetY: Double = 12.0,
        val idPrefix: String = "dup"
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class ChangeZOrder(
        val targets: Set<ObjectId>,
        val command: ZOrderCommand
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class MoveToLayer(
        val targets: Set<ObjectId>,
        val targetLayerId: LayerId
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class Group(
        val targets: Set<ObjectId>,
        val newGroupId: ObjectId
    ) : ObjectAction {
        val frozenTargets: Set<ObjectId> = java.util.Set.copyOf(targets)
    }

    data class Ungroup(
        val groupIds: Set<ObjectId>
    ) : ObjectAction {
        val frozenGroupIds: Set<ObjectId> = java.util.Set.copyOf(groupIds)
    }

    data class Erase(
        val pathPoints: List<DocPoint>,
        val spec: EraserSpec = EraserSpec(),
        val splitIdPrefix: String = "erased"
    ) : ObjectAction {
        val frozenPathPoints: List<DocPoint> = java.util.List.copyOf(pathPoints)
    }

    data class Paste(
        val bundle: ClipboardBundle,
        val targetCenter: DocPoint? = null,
        val targetLayerId: LayerId? = null,
        val idPrefix: String = "paste"
    ) : ObjectAction

    sealed interface ShapeAction : ObjectAction {
        data class CreateShape(
            val objectId: ObjectId,
            val payload: ShapePayload,
            val bounds: DocRect,
            val replaceStrokeId: ObjectId? = null
        ) : ShapeAction

        data class UpdateShapeVertices(
            val targetId: ObjectId,
            val vertices: List<DocPoint>,
            val controlPoints: List<DocPoint> = emptyList()
        ) : ShapeAction {
            val frozenVertices: List<DocPoint> = java.util.List.copyOf(vertices)
            val frozenControlPoints: List<DocPoint> = java.util.List.copyOf(controlPoints)
        }

        data class UpdateShapeAppearance(
            val targetId: ObjectId,
            val strokeColor: Int? = null,
            val fillColor: Int? = null,
            val clearFill: Boolean = false,
            val strokeWidthPt: Double? = null,
            val cornerRadiusPt: Double? = null,
            val startArrowhead: ArrowheadStyle? = null,
            val endArrowhead: ArrowheadStyle? = null,
            val shapeType: ShapeType? = null
        ) : ShapeAction

        data class CreateConnector(
            val connectorId: ObjectId,
            val sourceId: ObjectId,
            val targetId: ObjectId,
            val sourceAnchor: ConnectorAnchor = ConnectorAnchor(AnchorSide.RIGHT, 0.5),
            val targetAnchor: ConnectorAnchor = ConnectorAnchor(AnchorSide.LEFT, 0.5),
            val controlPoints: List<DocPoint> = emptyList(),
            val strokeColor: Int = 0xFF000000.toInt(),
            val strokeWidthPt: Double = 1.5,
            val isDirected: Boolean = true,
            val startArrowhead: ArrowheadStyle = ArrowheadStyle.NONE,
            val endArrowhead: ArrowheadStyle = ArrowheadStyle.OPEN_ARROW,
            val danglingPolicy: DanglingEndpointPolicy = DanglingEndpointPolicy.DELETE_CONNECTOR
        ) : ShapeAction {
            val frozenControlPoints: List<DocPoint> = java.util.List.copyOf(controlPoints)
        }

        data class UpdateConnectorEndpoints(
            val connectorId: ObjectId,
            val sourceId: ObjectId? = null,
            val targetId: ObjectId? = null,
            val sourceAnchor: ConnectorAnchor? = null,
            val targetAnchor: ConnectorAnchor? = null,
            val controlPoints: List<DocPoint>? = null,
            val danglingPolicy: DanglingEndpointPolicy? = null
        ) : ShapeAction {
            val frozenControlPoints: List<DocPoint>? = controlPoints?.let { java.util.List.copyOf(it) }
        }

        data class QuickDiagramCreateAndConnect(
            val sourceId: ObjectId,
            val newShapeId: ObjectId,
            val newShapePayload: ShapePayload,
            val newShapeBounds: DocRect,
            val connectorId: ObjectId,
            val sourceAnchor: ConnectorAnchor = ConnectorAnchor(AnchorSide.RIGHT, 0.5),
            val targetAnchor: ConnectorAnchor = ConnectorAnchor(AnchorSide.LEFT, 0.5),
            val strokeColor: Int = 0xFF000000.toInt(),
            val strokeWidthPt: Double = 1.5,
            val danglingPolicy: DanglingEndpointPolicy = DanglingEndpointPolicy.DELETE_CONNECTOR
        ) : ShapeAction
    }
}
