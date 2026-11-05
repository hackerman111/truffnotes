package inkspace.core.objects

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.SpaceId
import inkspace.core.limits.Limits
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.SceneObject
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.LinkAnchorPayload
import inkspace.core.payload.ObjectPayload
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.StickyPayload
import inkspace.core.payload.StrokePayload
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

data class PlanContext(
    val activeSpaceId: SpaceId = SpaceId("space-1"),
    val activePageId: PageId = PageId("page-1"),
    val activeLayerId: LayerId = LayerId("layer-1"),
    val enteredGroupId: ObjectId? = null,
    val gestureId: GestureId = GestureId("gesture-obj-1"),
    val geometryPort: GeometryPort? = null,
    val limits: Limits = Limits()
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
}
