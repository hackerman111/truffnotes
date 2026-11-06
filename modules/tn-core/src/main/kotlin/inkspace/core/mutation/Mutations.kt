package inkspace.core.mutation

import inkspace.core.codec.CoreCodec
import inkspace.core.codec.WriteRecord
import inkspace.core.codec.WriteSet
import inkspace.core.error.EditError
import inkspace.core.id.AssetId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.DirectoryEntry
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.ObjectStyle
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.outcome.Outcome
import inkspace.core.payload.referencedAssetIds
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.ObjectPayload
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Mutation

@Serializable
@SerialName("insert_object")
data class InsertObject(
    val space: SpaceId,
    val layer: LayerId,
    val id: ObjectId,
    val payload: ObjectPayload,
    val bounds: DocRect,
    val transform: AffineTransform2D = AffineTransform2D.IDENTITY,
    val style: ObjectStyle = ObjectStyle(),
    val isLocked: Boolean = false,
    val targetVersion: Long = 1L
) : Mutation

@Serializable
@SerialName("remove_object")
data class RemoveObject(
    val id: ObjectId
) : Mutation

@Serializable
@SerialName("update_payload")
data class UpdatePayload(
    val id: ObjectId,
    val newPayload: ObjectPayload,
    val newBounds: DocRect,
    val targetVersion: Long? = null
) : Mutation

@Serializable
@SerialName("move_object")
data class MoveObject(
    val id: ObjectId,
    val deltaX: Double,
    val deltaY: Double,
    val targetVersion: Long? = null
) : Mutation {
    init {
        require(deltaX.isFinite() && deltaY.isFinite()) { "Move deltas must be finite: ($deltaX, $deltaY)" }
    }
}

@Serializable
@SerialName("transform_object")
data class TransformObject(
    val id: ObjectId,
    val newTransform: AffineTransform2D,
    val newBounds: DocRect,
    val targetVersion: Long? = null
) : Mutation

@Serializable
@SerialName("update_object_style")
data class UpdateObjectStyle(
    val id: ObjectId,
    val newStyle: ObjectStyle,
    val targetVersion: Long? = null
) : Mutation

@Serializable
@SerialName("set_object_lock")
data class SetObjectLock(
    val id: ObjectId,
    val isLocked: Boolean,
    val targetVersion: Long? = null
) : Mutation

@Serializable
@SerialName("add_layer")
data class AddLayer(
    val layer: LayerDescriptor,
    val index: Int = -1
) : Mutation

@Serializable
@SerialName("remove_layer")
data class RemoveLayer(
    val layerId: LayerId
) : Mutation

@Serializable
@SerialName("reorder_layer")
data class ReorderLayer(
    val orderedLayerIds: List<LayerId>
) : Mutation

@Serializable
@SerialName("set_layer_lock")
data class SetLayerLock(
    val layerId: LayerId,
    val isLocked: Boolean
) : Mutation

@Serializable
@SerialName("set_layer_style")
data class SetLayerStyle(
    val layerId: LayerId,
    val opacity: Float,
    val isVisible: Boolean,
    val name: String? = null
) : Mutation {
    init {
        require(opacity.isFinite() && opacity in 0.0f..1.0f) { "Layer opacity must be in [0, 1]: $opacity" }
    }
}

@Serializable
@SerialName("add_page")
data class AddPage(
    val page: PageDescriptor,
    val index: Int = -1
) : Mutation

@Serializable
@SerialName("remove_page")
data class RemovePage(
    val pageId: PageId
) : Mutation

@Serializable
@SerialName("reorder_page")
data class ReorderPage(
    val orderedPageIds: List<PageId>
) : Mutation

@Serializable
@SerialName("resize_page")
data class ResizePage(
    val pageId: PageId,
    val newWidthPt: Double,
    val newHeightPt: Double
) : Mutation {
    init {
        require(newWidthPt.isFinite() && newWidthPt > 0.0 && newHeightPt.isFinite() && newHeightPt > 0.0) {
            "New page size must be positive finite: ($newWidthPt, $newHeightPt)"
        }
    }
}

@Serializable
@SerialName("update_page_descriptor")
data class UpdatePageDescriptor(val page: PageDescriptor) : Mutation

@Serializable
@SerialName("update_layer_descriptor")
data class UpdateLayerDescriptor(val layer: LayerDescriptor) : Mutation

@Serializable
@SerialName("relocate_object")
data class RelocateObject(val id: ObjectId, val layer: LayerId, val space: SpaceId, val targetVersion: Long? = null) : Mutation

@Serializable
data class Precondition(
    val id: String,
    val expectedVersion: Long
)

@Serializable
data class EditPlan(
    val base: DocVersion,
    val preconditions: List<Precondition> = emptyList(),
    val mutations: List<Mutation>,
    val gesture: GestureId,
    val label: UndoLabel
) {
    init {
        require(mutations.isNotEmpty()) { "EditPlan must contain at least one mutation" }
    }
}

class ValidatedPlan internal constructor(
    val plan: EditPlan,
    internal val limits: Limits = Limits()
)

@Serializable
data class ChangeSet(
    val changedIds: Set<ObjectId>,
    val oldBounds: DocRect? = null,
    val newBounds: DocRect? = null,
    val changedPageIds: Set<PageId> = emptySet(),
    val changedLayerIds: Set<LayerId> = emptySet()
)

data class AppliedEdit(
    val revision: Revision,
    val changeSet: ChangeSet,
    val inversePlan: EditPlan,
    val writeSet: WriteSet
)

/**
 * Validates an EditPlan against the current DocumentRead state and limits.
 * Guarantees that either the entire plan is valid, or returns a typed EditError without partial mutation.
 */
fun validatePlan(document: DocumentRead, plan: EditPlan, limits: Limits): Outcome<ValidatedPlan, EditError> {
    if (plan.base.document != document.version.document || plan.base.revision != document.version.revision) {
        return Outcome.Failure(
            EditError.StaleRevision(
                expected = plan.base.revision.value,
                actual = document.version.revision.value
            )
        )
    }

    // Verify preconditions
    for (precondition in plan.preconditions) {
        val objId = ObjectId(precondition.id)
        when (document.objectState(objId)) {
            ResidencyState.Missing -> {
                return Outcome.Failure(
                    EditError.PreconditionFailed("Object ${precondition.id} missing for precondition")
                )
            }
            ResidencyState.NotResident -> {
                return Outcome.Failure(
                    EditError.EntityNotResident(precondition.id)
                )
            }
            ResidencyState.Present -> {
                val actualVer = document.getObjectVersion(objId)
                if (actualVer != null && actualVer != precondition.expectedVersion) {
                    return Outcome.Failure(
                        EditError.PreconditionFailed(
                            "Object ${precondition.id} expected version ${precondition.expectedVersion}, got $actualVer"
                        )
                    )
                }
            }
        }
    }

    val hasLayerCatalog = document.layers.isNotEmpty()
    val hasPageCatalog = document.pages.isNotEmpty()

    val stagedPages = ArrayList<PageDescriptor>(document.pages)
    val stagedPageMap = LinkedHashMap<PageId, PageDescriptor>().apply {
        for (p in stagedPages) put(p.id, p)
    }
    val stagedLayers = ArrayList<LayerDescriptor>(document.layers)
    val stagedLayerMap = LinkedHashMap<LayerId, LayerDescriptor>().apply {
        for (l in stagedLayers) put(l.id, l)
    }

    // Staged object modifications: null value means removed in this batch
    val stagedObjects = LinkedHashMap<ObjectId, SceneObject?>()
    var netInsertedObjects = 0
    var anyEffectiveChange = false

    fun currentResidency(id: ObjectId): ResidencyState {
        if (stagedObjects.containsKey(id)) {
            return if (stagedObjects[id] != null) ResidencyState.Present else ResidencyState.Missing
        }
        return document.objectState(id)
    }

    fun currentObject(id: ObjectId): SceneObject? {
        if (stagedObjects.containsKey(id)) {
            return stagedObjects[id]
        }
        val existing = document.getObject(id)
        if (existing != null) return existing
        // Fallback for lightweight FakeDocumentRead / anonymous DocumentRead in unit tests
        val payload = document.getObjectPayload(id)
        if (payload != null || document.objectState(id) == ResidencyState.Present) {
            return SceneObject(
                id = id,
                space = SpaceId("space-default"),
                layer = LayerId("layer-default"),
                bounds = DocRect(0.0, 0.0, 1.0, 1.0),
                payload = payload ?: inkspace.core.payload.StickyPayload(0, ""),
                isLocked = document.isLocked(id),
                version = document.getObjectVersion(id) ?: 1L
            )
        }
        return null
    }

    fun isObjectOrLayerLocked(id: ObjectId, obj: SceneObject, allowObjectUnlock: Boolean = false): Boolean {
        val layerLocked = if (stagedLayerMap.containsKey(obj.layer)) {
            stagedLayerMap[obj.layer]?.isLocked == true
        } else {
            document.isLayerLocked(obj.layer)
        }
        if (layerLocked) return true
        if (allowObjectUnlock) return false
        return if (stagedObjects.containsKey(id)) {
            obj.isLocked
        } else {
            obj.isLocked || document.isLocked(id)
        }
    }

    for (mutation in plan.mutations) {
        when (mutation) {
            is InsertObject -> {
                val state = currentResidency(mutation.id)
                if (state != ResidencyState.Missing) {
                    return Outcome.Failure(
                        EditError.PreconditionFailed("Cannot insert object ${mutation.id.value}: already exists ($state)")
                    )
                }
                if (hasLayerCatalog) {
                    val layer = stagedLayerMap[mutation.layer]
                        ?: return Outcome.Failure(EditError.EntityNotFound("Layer ${mutation.layer.value} not found"))
                    if (layer.isLocked) {
                        return Outcome.Failure(EditError.LockedEntity("Layer ${mutation.layer.value} is locked"))
                    }
                } else if (document.isLayerLocked(mutation.layer)) {
                    return Outcome.Failure(EditError.LockedEntity("Layer ${mutation.layer.value} is locked"))
                }

                val newObj = SceneObject(
                    id = mutation.id,
                    space = mutation.space,
                    layer = mutation.layer,
                    bounds = mutation.bounds,
                    payload = mutation.payload,
                    transform = mutation.transform,
                    style = mutation.style,
                    isLocked = mutation.isLocked,
                    version = mutation.targetVersion
                )
                stagedObjects[mutation.id] = newObj
                netInsertedObjects++
                anyEffectiveChange = true
            }

            is RemoveObject -> {
                when (currentResidency(mutation.id)) {
                    ResidencyState.Missing -> {
                        return Outcome.Failure(
                            EditError.EntityNotFound("Cannot remove object ${mutation.id.value}: not found")
                        )
                    }
                    ResidencyState.NotResident -> {
                        return Outcome.Failure(EditError.EntityNotResident(mutation.id.value))
                    }
                    ResidencyState.Present -> {}
                }
                val obj = currentObject(mutation.id)!!
                if (isObjectOrLayerLocked(mutation.id, obj)) {
                    return Outcome.Failure(EditError.LockedEntity("Cannot remove locked object ${mutation.id.value}"))
                }
                stagedObjects[mutation.id] = null
                netInsertedObjects--
                anyEffectiveChange = true
            }

            is UpdatePayload -> {
                when (currentResidency(mutation.id)) {
                    ResidencyState.Missing -> {
                        return Outcome.Failure(
                            EditError.EntityNotFound("Cannot update object ${mutation.id.value}: not found")
                        )
                    }
                    ResidencyState.NotResident -> {
                        return Outcome.Failure(EditError.EntityNotResident(mutation.id.value))
                    }
                    ResidencyState.Present -> {}
                }
                val obj = currentObject(mutation.id)!!
                if (isObjectOrLayerLocked(mutation.id, obj)) {
                    return Outcome.Failure(EditError.LockedEntity("Cannot update locked object ${mutation.id.value}"))
                }
                val nextVer = mutation.targetVersion ?: (obj.version + 1L)
                val updated = obj.copy(
                    payload = mutation.newPayload,
                    bounds = mutation.newBounds,
                    version = nextVer,
                    payloadDigest = CoreCodec.computePayloadDigest(mutation.newPayload)
                )
                if (obj.payload != updated.payload || obj.bounds != updated.bounds) {
                    anyEffectiveChange = true
                }
                stagedObjects[mutation.id] = updated
            }

            is MoveObject -> {
                when (currentResidency(mutation.id)) {
                    ResidencyState.Missing -> {
                        return Outcome.Failure(
                            EditError.EntityNotFound("Cannot move object ${mutation.id.value}: not found")
                        )
                    }
                    ResidencyState.NotResident -> {
                        return Outcome.Failure(EditError.EntityNotResident(mutation.id.value))
                    }
                    ResidencyState.Present -> {}
                }
                val obj = currentObject(mutation.id)!!
                if (isObjectOrLayerLocked(mutation.id, obj)) {
                    return Outcome.Failure(EditError.LockedEntity("Cannot move locked object ${mutation.id.value}"))
                }
                if (mutation.deltaX != 0.0 || mutation.deltaY != 0.0) {
                    anyEffectiveChange = true
                }
                val nextBounds = try {
                    obj.bounds.translate(mutation.deltaX, mutation.deltaY)
                } catch (e: IllegalArgumentException) {
                    return Outcome.Failure(EditError.NonFiniteCoordinate(e.message ?: "Bounds out of world envelope"))
                }
                val nextVer = mutation.targetVersion ?: (obj.version + 1L)
                stagedObjects[mutation.id] = obj.copy(
                    bounds = nextBounds,
                    transform = obj.transform.translate(mutation.deltaX, mutation.deltaY),
                    version = nextVer
                )
            }

            is TransformObject -> {
                when (currentResidency(mutation.id)) {
                    ResidencyState.Missing -> {
                        return Outcome.Failure(
                            EditError.EntityNotFound("Cannot transform object ${mutation.id.value}: not found")
                        )
                    }
                    ResidencyState.NotResident -> {
                        return Outcome.Failure(EditError.EntityNotResident(mutation.id.value))
                    }
                    ResidencyState.Present -> {}
                }
                val obj = currentObject(mutation.id)!!
                if (isObjectOrLayerLocked(mutation.id, obj)) {
                    return Outcome.Failure(EditError.LockedEntity("Cannot transform locked object ${mutation.id.value}"))
                }
                if (obj.transform != mutation.newTransform || obj.bounds != mutation.newBounds) {
                    anyEffectiveChange = true
                }
                val nextVer = mutation.targetVersion ?: (obj.version + 1L)
                stagedObjects[mutation.id] = obj.copy(
                    transform = mutation.newTransform,
                    bounds = mutation.newBounds,
                    version = nextVer
                )
            }

            is UpdateObjectStyle -> {
                when (currentResidency(mutation.id)) {
                    ResidencyState.Missing -> {
                        return Outcome.Failure(
                            EditError.EntityNotFound("Cannot style object ${mutation.id.value}: not found")
                        )
                    }
                    ResidencyState.NotResident -> {
                        return Outcome.Failure(EditError.EntityNotResident(mutation.id.value))
                    }
                    ResidencyState.Present -> {}
                }
                val obj = currentObject(mutation.id)!!
                if (isObjectOrLayerLocked(mutation.id, obj)) {
                    return Outcome.Failure(EditError.LockedEntity("Cannot style locked object ${mutation.id.value}"))
                }
                if (obj.style != mutation.newStyle) {
                    anyEffectiveChange = true
                }
                val nextVer = mutation.targetVersion ?: (obj.version + 1L)
                stagedObjects[mutation.id] = obj.copy(
                    style = mutation.newStyle,
                    version = nextVer
                )
            }

            is SetObjectLock -> {
                when (currentResidency(mutation.id)) {
                    ResidencyState.Missing -> {
                        return Outcome.Failure(
                            EditError.EntityNotFound("Cannot lock/unlock object ${mutation.id.value}: not found")
                        )
                    }
                    ResidencyState.NotResident -> {
                        return Outcome.Failure(EditError.EntityNotResident(mutation.id.value))
                    }
                    ResidencyState.Present -> {}
                }
                val obj = currentObject(mutation.id)!!
                if (isObjectOrLayerLocked(mutation.id, obj, allowObjectUnlock = true)) {
                    return Outcome.Failure(EditError.LockedEntity("Containing layer ${obj.layer.value} is locked"))
                }
                if (obj.isLocked != mutation.isLocked) {
                    anyEffectiveChange = true
                }
                val nextVer = mutation.targetVersion ?: (obj.version + 1L)
                stagedObjects[mutation.id] = obj.copy(
                    isLocked = mutation.isLocked,
                    version = nextVer
                )
            }

            is AddLayer -> {
                if (stagedLayerMap.containsKey(mutation.layer.id)) {
                    return Outcome.Failure(
                        EditError.PreconditionFailed("Layer ${mutation.layer.id.value} already exists")
                    )
                }
                if (hasPageCatalog && !stagedPageMap.containsKey(mutation.layer.pageId)) {
                    return Outcome.Failure(
                        EditError.EntityNotFound("Page ${mutation.layer.pageId.value} not found for layer")
                    )
                }
                val idx = if (mutation.index in 0..stagedLayers.size) mutation.index else stagedLayers.size
                stagedLayers.add(idx, mutation.layer)
                stagedLayerMap[mutation.layer.id] = mutation.layer
                anyEffectiveChange = true
            }

            is RemoveLayer -> {
                val existing = stagedLayerMap[mutation.layerId]
                    ?: return Outcome.Failure(EditError.EntityNotFound("Layer ${mutation.layerId.value} not found"))
                if (existing.isLocked) {
                    return Outcome.Failure(EditError.LockedEntity("Layer ${mutation.layerId.value} is locked"))
                }
                if (stagedLayers.size <= 1) {
                    return Outcome.Failure(EditError.PreconditionFailed("Cannot remove the last layer"))
                }
                stagedLayers.removeIf { it.id == mutation.layerId }
                stagedLayerMap.remove(mutation.layerId)
                anyEffectiveChange = true
            }

            is ReorderLayer -> {
                if (mutation.orderedLayerIds.size != stagedLayers.size ||
                    mutation.orderedLayerIds.toSet() != stagedLayerMap.keys
                ) {
                    return Outcome.Failure(EditError.PreconditionFailed("Invalid layer reorder set"))
                }
                val currentIds = stagedLayers.map { it.id }
                if (currentIds != mutation.orderedLayerIds) {
                    val reordered = mutation.orderedLayerIds.map { stagedLayerMap[it]!! }
                    stagedLayers.clear()
                    stagedLayers.addAll(reordered)
                    anyEffectiveChange = true
                }
            }

            is SetLayerLock -> {
                val existing = stagedLayerMap[mutation.layerId]
                    ?: return Outcome.Failure(EditError.EntityNotFound("Layer ${mutation.layerId.value} not found"))
                if (existing.isLocked != mutation.isLocked) {
                    val updated = existing.copy(isLocked = mutation.isLocked)
                    stagedLayerMap[mutation.layerId] = updated
                    val idx = stagedLayers.indexOfFirst { it.id == mutation.layerId }
                    if (idx >= 0) stagedLayers[idx] = updated
                    anyEffectiveChange = true
                }
            }

            is SetLayerStyle -> {
                val existing = stagedLayerMap[mutation.layerId]
                    ?: return Outcome.Failure(EditError.EntityNotFound("Layer ${mutation.layerId.value} not found"))
                if (existing.isLocked) {
                    return Outcome.Failure(EditError.LockedEntity("Layer ${mutation.layerId.value} is locked"))
                }
                val updated = existing.copy(
                    opacity = mutation.opacity,
                    isVisible = mutation.isVisible,
                    name = mutation.name ?: existing.name
                )
                if (updated != existing) {
                    stagedLayerMap[mutation.layerId] = updated
                    val idx = stagedLayers.indexOfFirst { it.id == mutation.layerId }
                    if (idx >= 0) stagedLayers[idx] = updated
                    anyEffectiveChange = true
                }
            }

            is AddPage -> {
                if (stagedPageMap.containsKey(mutation.page.id)) {
                    return Outcome.Failure(
                        EditError.PreconditionFailed("Page ${mutation.page.id.value} already exists")
                    )
                }
                val idx = if (mutation.index in 0..stagedPages.size) mutation.index else stagedPages.size
                stagedPages.add(idx, mutation.page.copy(metadata=mutation.page.metadata.frozen()))
                stagedPageMap[mutation.page.id] = mutation.page.copy(metadata=mutation.page.metadata.frozen())
                anyEffectiveChange = true
            }

            is RemovePage -> {
                if (!stagedPageMap.containsKey(mutation.pageId)) {
                    return Outcome.Failure(EditError.EntityNotFound("Page ${mutation.pageId.value} not found"))
                }
                if (stagedPages.size <= 1) {
                    return Outcome.Failure(EditError.PreconditionFailed("Cannot remove the last page"))
                }
                stagedPages.removeIf { it.id == mutation.pageId }
                stagedPageMap.remove(mutation.pageId)
                anyEffectiveChange = true
            }

            is ReorderPage -> {
                if (mutation.orderedPageIds.size != stagedPages.size ||
                    mutation.orderedPageIds.toSet() != stagedPageMap.keys
                ) {
                    return Outcome.Failure(EditError.PreconditionFailed("Invalid page reorder set"))
                }
                val currentIds = stagedPages.map { it.id }
                if (currentIds != mutation.orderedPageIds) {
                    val reordered = mutation.orderedPageIds.map { stagedPageMap[it]!! }
                    stagedPages.clear()
                    stagedPages.addAll(reordered)
                    anyEffectiveChange = true
                }
            }

            is UpdatePageDescriptor -> {
                val existing = stagedPageMap[mutation.page.id]
                    ?: return Outcome.Failure(EditError.EntityNotFound("Page missing"))
                if (existing.spaceId != mutation.page.spaceId) return Outcome.Failure(EditError.PreconditionFailed("Page space is immutable"))
                val next = mutation.page.copy(metadata=mutation.page.metadata.frozen())
                stagedPageMap[next.id] = next
                stagedPages[stagedPages.indexOfFirst { it.id == next.id }] = next
                anyEffectiveChange = anyEffectiveChange || next != existing
            }
            is UpdateLayerDescriptor -> {
                val existing = stagedLayerMap[mutation.layer.id]
                    ?: return Outcome.Failure(EditError.EntityNotFound("Layer missing"))
                if (existing.pageId != mutation.layer.pageId || existing.isLocked || existing.isLocked != mutation.layer.isLocked) return Outcome.Failure(EditError.LockedEntity("Layer is locked or page changed"))
                stagedLayerMap[mutation.layer.id] = mutation.layer
                stagedLayers[stagedLayers.indexOfFirst { it.id == mutation.layer.id }] = mutation.layer
                anyEffectiveChange = anyEffectiveChange || existing != mutation.layer
            }
            is RelocateObject -> {
                val obj = currentObject(mutation.id) ?: return Outcome.Failure(EditError.EntityNotFound("Object missing"))
                if (isObjectOrLayerLocked(obj.id,obj)) return Outcome.Failure(EditError.LockedEntity("Object or layer locked"))
                val layer = stagedLayerMap[mutation.layer] ?: return Outcome.Failure(EditError.EntityNotFound("Target layer missing"))
                val page = stagedPageMap[layer.pageId] ?: return Outcome.Failure(EditError.EntityNotFound("Target page missing"))
                if(layer.isLocked || !layer.isVisible || page.spaceId != mutation.space) return Outcome.Failure(EditError.PreconditionFailed("Invalid target layer/space"))
                stagedObjects[obj.id] = obj.copy(layer=mutation.layer,space=mutation.space,version=mutation.targetVersion ?: obj.version+1)
                anyEffectiveChange = anyEffectiveChange || obj.layer != mutation.layer || obj.space != mutation.space
            }
            is ResizePage -> {
                val existing = stagedPageMap[mutation.pageId]
                    ?: return Outcome.Failure(EditError.EntityNotFound("Page ${mutation.pageId.value} not found"))
                val updated = existing.copy(widthPt = mutation.newWidthPt, heightPt = mutation.newHeightPt)
                if (updated != existing) {
                    stagedPageMap[mutation.pageId] = updated
                    val idx = stagedPages.indexOfFirst { it.id == mutation.pageId }
                    if (idx >= 0) stagedPages[idx] = updated
                    anyEffectiveChange = true
                }
            }
        }
    }

    val totalObjects = document.totalObjectCount + netInsertedObjects
    if (totalObjects > limits.maxObjectsPerSpace) {
        return Outcome.Failure(
            EditError.LimitExceeded(
                limit = "maxObjectsPerSpace",
                current = totalObjects.toLong(),
                max = limits.maxObjectsPerSpace.toLong()
            )
        )
    }

    if (!anyEffectiveChange) {
        return Outcome.Failure(EditError.NoOpPlan("EditPlan produces no effective change"))
    }

    // Validate group hierarchy (member existence, single parent ownership, acyclicity, and maxGroupDepth)
    val touchedGroups = stagedObjects.values.filterNotNull().filter { it.payload is GroupPayload }
    if (touchedGroups.isNotEmpty()) {
        val groupValidation = validateGroups(document, stagedObjects, limits)
        if (groupValidation != null) {
            return Outcome.Failure(groupValidation)
        }
    }

    return Outcome.Success(ValidatedPlan(plan, limits))
}

private fun validateGroups(
    document: DocumentRead,
    stagedObjects: Map<ObjectId, SceneObject?>,
    limits: Limits
): EditError? {
    fun resolveObject(id: ObjectId): SceneObject? {
        if (stagedObjects.containsKey(id)) return stagedObjects[id]
        return document.getObject(id)
    }

    fun resolveResidency(id: ObjectId): ResidencyState {
        if (stagedObjects.containsKey(id)) {
            return if (stagedObjects[id] != null) ResidencyState.Present else ResidencyState.Missing
        }
        return document.objectState(id)
    }

    // Collect all active groups touching staged objects or their ancestors/descendants
    val parentByChild = HashMap<ObjectId, ObjectId>()

    // Seed existing parent mappings for any non-staged groups
    val allGroups = LinkedHashMap<ObjectId, GroupPayload>()
    val queue = ArrayDeque<ObjectId>()

    for ((id, obj) in stagedObjects) {
        if (obj != null && obj.payload is GroupPayload) {
            allGroups[id] = obj.payload
            queue.add(id)
        }
        val existingParent = document.parentGroupOf(id)
        if (existingParent != null && !stagedObjects.containsKey(existingParent)) {
            queue.add(existingParent)
        }
    }

    while (queue.isNotEmpty()) {
        val gId = queue.removeFirst()
        val gObj = resolveObject(gId) ?: continue
        val gPayload = gObj.payload as? GroupPayload ?: continue
        allGroups[gId] = gPayload

        val parentOfG = document.parentGroupOf(gId)
        if (parentOfG != null && !allGroups.containsKey(parentOfG)) {
            queue.add(parentOfG)
        }

        for (memberId in gPayload.members) {
            when (resolveResidency(memberId)) {
                ResidencyState.Missing -> {
                    return EditError.EntityNotFound("Group ${gId.value} references missing member ${memberId.value}")
                }
                ResidencyState.NotResident -> {
                    return EditError.EntityNotResident(memberId.value)
                }
                ResidencyState.Present -> {}
            }
            val memberObj = resolveObject(memberId)
                ?: return EditError.EntityNotFound("Group ${gId.value} references missing member ${memberId.value}")
            if (memberObj.space != gObj.space) {
                return EditError.PreconditionFailed(
                    "Group ${gId.value} member ${memberId.value} belongs to different space"
                )
            }
            if (memberId == gId) {
                return EditError.CycleDetected(gId.value)
            }
            val existingOwner = parentByChild.put(memberId, gId)
            if (existingOwner != null && existingOwner != gId) {
                return EditError.PreconditionFailed(
                    "Object ${memberId.value} cannot belong to both ${existingOwner.value} and ${gId.value}"
                )
            }
            val docOwner = document.parentGroupOf(memberId)
            if (docOwner != null && docOwner != gId && !stagedObjects.containsKey(docOwner)) {
                val docOwnerObj = resolveObject(docOwner)
                val docOwnerGroup = docOwnerObj?.payload as? GroupPayload
                if (docOwnerGroup != null && memberId in docOwnerGroup.members) {
                    return EditError.PreconditionFailed(
                        "Object ${memberId.value} already belongs to group ${docOwner.value}"
                    )
                }
            }
            if (memberObj.payload is GroupPayload && !allGroups.containsKey(memberId)) {
                queue.add(memberId)
            }
        }
    }

    // Cycle detection and depth calculation via DFS
    val visiting = HashSet<ObjectId>()
    val depthMemo = HashMap<ObjectId, Int>()

    fun computeDepth(groupId: ObjectId): Int {
        depthMemo[groupId]?.let { return it }
        if (!visiting.add(groupId)) {
            return -1 // cycle detected
        }
        val payload = allGroups[groupId] ?: (resolveObject(groupId)?.payload as? GroupPayload)
        var maxChildDepth = 0
        if (payload != null) {
            for (memberId in payload.members) {
                val memberObj = resolveObject(memberId)
                if (memberObj?.payload is GroupPayload || allGroups.containsKey(memberId)) {
                    val childDepth = computeDepth(memberId)
                    if (childDepth < 0) return -1
                    if (childDepth > maxChildDepth) maxChildDepth = childDepth
                }
            }
        }
        visiting.remove(groupId)
        val totalDepth = 1 + maxChildDepth
        depthMemo[groupId] = totalDepth
        return totalDepth
    }

    for (groupId in allGroups.keys) {
        val depth = computeDepth(groupId)
        if (depth < 0) {
            return EditError.CycleDetected(groupId.value)
        }
        if (depth > limits.maxGroupDepth) {
            return EditError.LimitExceeded(
                limit = "maxGroupDepth",
                current = depth.toLong(),
                max = limits.maxGroupDepth.toLong()
            )
        }
    }

    return null
}

/**
 * Applies a ValidatedPlan atomically to DocumentState, producing an AppliedEdit with exact ChangeSet,
 * inverse EditPlan, and versioned WriteSet.
 */
fun applyPlan(
    state: DocumentState,
    validatedPlan: ValidatedPlan,
    limits: Limits = validatedPlan.limits
): Outcome<AppliedEdit, EditError> {
    val plan = validatedPlan.plan
    // Re-validate against live state to prevent TOCTOU if state advanced
    val recheck = validatePlan(state, plan, limits)
    if (recheck is Outcome.Failure) {
        return recheck
    }

    val beforeSnapshot = state.snapshot()
    var workingDir = beforeSnapshot.directory
    val workingPages = ArrayList(beforeSnapshot.pages)
    val workingPageMap = LinkedHashMap<PageId, PageDescriptor>().apply {
        for (p in workingPages) put(p.id, p)
    }
    val workingLayers = ArrayList(beforeSnapshot.layers)
    val workingLayerMap = LinkedHashMap<LayerId, LayerDescriptor>().apply {
        for (l in workingLayers) put(l.id, l)
    }
    val workingParentGroups = LinkedHashMap<ObjectId, ObjectId>().apply {
        for (obj in workingDir.allResidentObjects()) {
            val p = obj.payload
            if (p is GroupPayload) {
                for (m in p.members) put(m, obj.id)
            }
        }
    }

    val touchedObjectIds = LinkedHashSet<ObjectId>()
    val beforeObjects = LinkedHashMap<ObjectId, SceneObject?>()
    val changedPageIds = LinkedHashSet<PageId>()
    val changedLayerIds = LinkedHashSet<LayerId>()
    val stepInverses = ArrayList<Mutation>(plan.mutations.size)

    fun recordBeforeObject(id: ObjectId) {
        if (touchedObjectIds.add(id)) {
            beforeObjects[id] = beforeSnapshot.getObject(id)
        }
    }

    for (mutation in plan.mutations) {
        when (mutation) {
            is InsertObject -> {
                recordBeforeObject(mutation.id)
                val newObj = SceneObject(
                    id = mutation.id,
                    space = mutation.space,
                    layer = mutation.layer,
                    bounds = mutation.bounds,
                    payload = mutation.payload,
                    transform = mutation.transform,
                    style = mutation.style,
                    isLocked = mutation.isLocked,
                    version = mutation.targetVersion
                )
                workingDir = workingDir.putEntry(DirectoryEntry.Present(newObj))
                if (newObj.payload is GroupPayload) {
                    for (m in newObj.payload.members) {
                        workingParentGroups[m] = newObj.id
                    }
                }
                stepInverses.add(RemoveObject(mutation.id))
            }

            is RemoveObject -> {
                recordBeforeObject(mutation.id)
                val prev = workingDir.getObject(mutation.id)!!
                if (prev.payload is GroupPayload) {
                    for (m in prev.payload.members) {
                        if (workingParentGroups[m] == prev.id) {
                            workingParentGroups.remove(m)
                        }
                    }
                }
                workingDir = workingDir.removeEntry(mutation.id)
                stepInverses.add(
                    InsertObject(
                        space = prev.space,
                        layer = prev.layer,
                        id = prev.id,
                        payload = prev.payload,
                        bounds = prev.bounds,
                        transform = prev.transform,
                        style = prev.style,
                        isLocked = prev.isLocked,
                        targetVersion = prev.version
                    )
                )
            }

            is UpdatePayload -> {
                recordBeforeObject(mutation.id)
                val prev = workingDir.getObject(mutation.id)!!
                if (prev.payload is GroupPayload) {
                    for (m in prev.payload.members) {
                        if (workingParentGroups[m] == prev.id) {
                            workingParentGroups.remove(m)
                        }
                    }
                }
                val nextVer = mutation.targetVersion ?: (prev.version + 1L)
                val nextObj = prev.copy(
                    payload = mutation.newPayload,
                    bounds = mutation.newBounds,
                    version = nextVer,
                    payloadDigest = CoreCodec.computePayloadDigest(mutation.newPayload)
                )
                workingDir = workingDir.putEntry(DirectoryEntry.Present(nextObj))
                if (nextObj.payload is GroupPayload) {
                    for (m in nextObj.payload.members) {
                        workingParentGroups[m] = nextObj.id
                    }
                }
                stepInverses.add(
                    UpdatePayload(
                        id = prev.id,
                        newPayload = prev.payload,
                        newBounds = prev.bounds,
                        targetVersion = prev.version
                    )
                )
            }

            is MoveObject -> {
                recordBeforeObject(mutation.id)
                val prev = workingDir.getObject(mutation.id)!!
                val nextVer = mutation.targetVersion ?: (prev.version + 1L)
                val nextObj = prev.copy(
                    bounds = prev.bounds.translate(mutation.deltaX, mutation.deltaY),
                    transform = prev.transform.translate(mutation.deltaX, mutation.deltaY),
                    version = nextVer
                )
                workingDir = workingDir.putEntry(DirectoryEntry.Present(nextObj))
                stepInverses.add(
                    TransformObject(
                        id = prev.id,
                        newTransform = prev.transform,
                        newBounds = prev.bounds,
                        targetVersion = prev.version
                    )
                )
            }

            is TransformObject -> {
                recordBeforeObject(mutation.id)
                val prev = workingDir.getObject(mutation.id)!!
                val nextVer = mutation.targetVersion ?: (prev.version + 1L)
                val nextObj = prev.copy(
                    transform = mutation.newTransform,
                    bounds = mutation.newBounds,
                    version = nextVer
                )
                workingDir = workingDir.putEntry(DirectoryEntry.Present(nextObj))
                stepInverses.add(
                    TransformObject(
                        id = prev.id,
                        newTransform = prev.transform,
                        newBounds = prev.bounds,
                        targetVersion = prev.version
                    )
                )
            }

            is UpdateObjectStyle -> {
                recordBeforeObject(mutation.id)
                val prev = workingDir.getObject(mutation.id)!!
                val nextVer = mutation.targetVersion ?: (prev.version + 1L)
                val nextObj = prev.copy(
                    style = mutation.newStyle,
                    version = nextVer
                )
                workingDir = workingDir.putEntry(DirectoryEntry.Present(nextObj))
                stepInverses.add(
                    UpdateObjectStyle(
                        id = prev.id,
                        newStyle = prev.style,
                        targetVersion = prev.version
                    )
                )
            }

            is SetObjectLock -> {
                recordBeforeObject(mutation.id)
                val prev = workingDir.getObject(mutation.id)!!
                val nextVer = mutation.targetVersion ?: (prev.version + 1L)
                val nextObj = prev.copy(
                    isLocked = mutation.isLocked,
                    version = nextVer
                )
                workingDir = workingDir.putEntry(DirectoryEntry.Present(nextObj))
                stepInverses.add(
                    SetObjectLock(
                        id = prev.id,
                        isLocked = prev.isLocked,
                        targetVersion = prev.version
                    )
                )
            }

            is AddLayer -> {
                val idx = if (mutation.index in 0..workingLayers.size) mutation.index else workingLayers.size
                workingLayers.add(idx, mutation.layer)
                workingLayerMap[mutation.layer.id] = mutation.layer
                changedLayerIds.add(mutation.layer.id)
                stepInverses.add(RemoveLayer(mutation.layer.id))
            }

            is RemoveLayer -> {
                val idx = workingLayers.indexOfFirst { it.id == mutation.layerId }
                val prev = workingLayerMap.remove(mutation.layerId)!!
                workingLayers.removeAt(idx)
                changedLayerIds.add(mutation.layerId)
                stepInverses.add(AddLayer(prev, index = idx))
            }

            is ReorderLayer -> {
                val prevOrder = workingLayers.map { it.id }
                val reordered = mutation.orderedLayerIds.map { workingLayerMap[it]!! }
                workingLayers.clear()
                workingLayers.addAll(reordered)
                changedLayerIds.addAll(mutation.orderedLayerIds)
                stepInverses.add(ReorderLayer(prevOrder))
            }

            is SetLayerLock -> {
                val prev = workingLayerMap[mutation.layerId]!!
                val next = prev.copy(isLocked = mutation.isLocked)
                workingLayerMap[mutation.layerId] = next
                val idx = workingLayers.indexOfFirst { it.id == mutation.layerId }
                if (idx >= 0) workingLayers[idx] = next
                changedLayerIds.add(mutation.layerId)
                stepInverses.add(SetLayerLock(mutation.layerId, prev.isLocked))
            }

            is SetLayerStyle -> {
                val prev = workingLayerMap[mutation.layerId]!!
                val next = prev.copy(
                    opacity = mutation.opacity,
                    isVisible = mutation.isVisible,
                    name = mutation.name ?: prev.name
                )
                workingLayerMap[mutation.layerId] = next
                val idx = workingLayers.indexOfFirst { it.id == mutation.layerId }
                if (idx >= 0) workingLayers[idx] = next
                changedLayerIds.add(mutation.layerId)
                stepInverses.add(SetLayerStyle(mutation.layerId, prev.opacity, prev.isVisible, prev.name))
            }

            is AddPage -> {
                val idx = if (mutation.index in 0..workingPages.size) mutation.index else workingPages.size
                workingPages.add(idx, mutation.page.copy(metadata=mutation.page.metadata.frozen()))
                workingPageMap[mutation.page.id] = mutation.page.copy(metadata=mutation.page.metadata.frozen())
                changedPageIds.add(mutation.page.id)
                stepInverses.add(RemovePage(mutation.page.id))
            }

            is RemovePage -> {
                val idx = workingPages.indexOfFirst { it.id == mutation.pageId }
                val prev = workingPageMap.remove(mutation.pageId)!!
                workingPages.removeAt(idx)
                changedPageIds.add(mutation.pageId)
                stepInverses.add(AddPage(prev, index = idx))
            }

            is ReorderPage -> {
                val prevOrder = workingPages.map { it.id }
                val reordered = mutation.orderedPageIds.map { workingPageMap[it]!! }
                workingPages.clear()
                workingPages.addAll(reordered)
                changedPageIds.addAll(mutation.orderedPageIds)
                stepInverses.add(ReorderPage(prevOrder))
            }

            is UpdatePageDescriptor -> {
                val prev = workingPageMap.getValue(mutation.page.id)
                val next = mutation.page.copy(metadata=mutation.page.metadata.frozen())
                workingPageMap[next.id] = next
                workingPages[workingPages.indexOfFirst { it.id == next.id }] = next
                changedPageIds.add(next.id)
                stepInverses.add(UpdatePageDescriptor(prev))
            }
            is UpdateLayerDescriptor -> {
                val prev = workingLayerMap.getValue(mutation.layer.id)
                workingLayerMap[mutation.layer.id] = mutation.layer
                workingLayers[workingLayers.indexOfFirst { it.id == mutation.layer.id }] = mutation.layer
                changedLayerIds.add(mutation.layer.id)
                stepInverses.add(UpdateLayerDescriptor(prev))
            }
            is RelocateObject -> {
                recordBeforeObject(mutation.id)
                val prev = requireNotNull(workingDir.getObject(mutation.id))
                workingDir = workingDir.putEntry(DirectoryEntry.Present(prev.copy(layer=mutation.layer,space=mutation.space,version=mutation.targetVersion ?: prev.version+1)))
                stepInverses.add(RelocateObject(prev.id,prev.layer,prev.space,prev.version))
            }
            is ResizePage -> {
                val prev = workingPageMap[mutation.pageId]!!
                val next = prev.copy(widthPt = mutation.newWidthPt, heightPt = mutation.newHeightPt)
                workingPageMap[mutation.pageId] = next
                val idx = workingPages.indexOfFirst { it.id == mutation.pageId }
                if (idx >= 0) workingPages[idx] = next
                changedPageIds.add(mutation.pageId)
                stepInverses.add(ResizePage(mutation.pageId, prev.widthPt, prev.heightPt))
            }
        }
    }

    // Compute exact ChangeSet and WriteRecords from net before/after differences
    val changedIds = LinkedHashSet<ObjectId>()
    var oldBoundsUnion: DocRect? = null
    var newBoundsUnion: DocRect? = null
    val writeRecords = ArrayList<WriteRecord>()
    val referencedAssets = LinkedHashSet<AssetId>()

    for (id in touchedObjectIds) {
        val beforeObj = beforeObjects[id]
        val afterObj = workingDir.getObject(id)
        if (beforeObj != afterObj) {
            changedIds.add(id)
            if (beforeObj != null) {
                oldBoundsUnion = oldBoundsUnion?.union(beforeObj.bounds) ?: beforeObj.bounds
            }
            if (afterObj != null) {
                newBoundsUnion = newBoundsUnion?.union(afterObj.bounds) ?: afterObj.bounds
                val p = afterObj.payload
                referencedAssets.addAll(p.referencedAssetIds())
                val op = if (beforeObj == null) "insert" else "update"
                writeRecords.add(
                    WriteRecord(
                        entityKind = "object",
                        entityId = id.value,
                        operation = op,
                        newVersion = afterObj.version,
                        expectedDigest = afterObj.payloadDigest
                    )
                )
            } else {
                writeRecords.add(
                    WriteRecord(
                        entityKind = "object",
                        entityId = id.value,
                        operation = "remove",
                        newVersion = 0L,
                        expectedDigest = 0L
                    )
                )
            }
        }
    }

    for (pageId in changedPageIds) {
        val afterPage = workingPageMap[pageId]
        val digest = if (afterPage != null) {
            CoreCodec.fnv1a64("${afterPage.id.value}:${afterPage.widthPt}:${afterPage.heightPt}".encodeToByteArray())
        } else {
            0L
        }
        writeRecords.add(
            WriteRecord(
                entityKind = "page",
                entityId = pageId.value,
                operation = if (afterPage != null) "upsert" else "remove",
                newVersion = 1L,
                expectedDigest = digest
            )
        )
    }

    for (layerId in changedLayerIds) {
        val afterLayer = workingLayerMap[layerId]
        val digest = if (afterLayer != null) {
            CoreCodec.fnv1a64(
                "${afterLayer.id.value}:${afterLayer.opacity}:${afterLayer.isVisible}:${afterLayer.isLocked}"
                    .encodeToByteArray()
            )
        } else {
            0L
        }
        writeRecords.add(
            WriteRecord(
                entityKind = "layer",
                entityId = layerId.value,
                operation = if (afterLayer != null) "upsert" else "remove",
                newVersion = 1L,
                expectedDigest = digest
            )
        )
    }

    for(page in workingPages) {
        page.metadata.template?.asset?.let(referencedAssets::add)
        page.metadata.pdfBackground?.asset?.let(referencedAssets::add)
    }
    val nextRevision = beforeSnapshot.version.revision.next()
    val nextDocVersion = DocVersion(beforeSnapshot.version.document, nextRevision)

    val inverseMutations = stepInverses.asReversed()
    val inversePlan = EditPlan(
        base = nextDocVersion,
        mutations = inverseMutations,
        gesture = plan.gesture,
        label = UndoLabel("Undo: ${plan.label.value}")
    )

    val changeSet = ChangeSet(
        changedIds = changedIds,
        oldBounds = oldBoundsUnion,
        newBounds = newBoundsUnion,
        changedPageIds = changedPageIds,
        changedLayerIds = changedLayerIds
    )

    val writeSet = WriteSet(
        schemaVersion = CoreCodec.CURRENT_SCHEMA_VERSION,
        documentId = beforeSnapshot.version.document,
        baseRevision = beforeSnapshot.version.revision,
        newRevision = nextRevision,
        plan = plan,
        records = writeRecords,
        referencedAssets = referencedAssets
    )

    state.commitTransition(
        nextVersion = nextDocVersion,
        nextDirectory = workingDir,
        nextPages = workingPages.toList(),
        nextLayers = workingLayers.toList(),
        nextPageMap = workingPageMap,
        nextLayerMap = workingLayerMap,
        nextParentGroups = workingParentGroups
    )

    return Outcome.Success(
        AppliedEdit(
            revision = nextRevision,
            changeSet = changeSet,
            inversePlan = inversePlan,
            writeSet = writeSet
        )
    )
}

/**
 * Replays a persisted WriteSet through the single validatePlan -> applyPlan reducer,
 * verifying post-state record versions and deterministic payload digests before committing.
 */
fun replayWriteSet(
    state: DocumentState,
    writeSet: WriteSet,
    limits: Limits = Limits()
): Outcome<AppliedEdit, EditError> {
    if (writeSet.schemaVersion != CoreCodec.CURRENT_SCHEMA_VERSION) {
        return Outcome.Failure(EditError.InvalidPayload("Unsupported WriteSet schemaVersion: ${writeSet.schemaVersion}"))
    }
    if (writeSet.documentId != state.version.document || writeSet.baseRevision != state.version.revision) {
        return Outcome.Failure(
            EditError.StaleRevision(
                expected = writeSet.baseRevision.value,
                actual = state.version.revision.value
            )
        )
    }

    val beforeSnap = state.snapshot()
    val validated = validatePlan(state, writeSet.plan, limits)
    if (validated is Outcome.Failure) return validated

    val appliedOutcome = applyPlan(state, validated.getOrNull()!!, limits)
    if (appliedOutcome is Outcome.Failure) return appliedOutcome
    val applied = appliedOutcome.getOrNull()!!

    // Verify every WriteRecord against the post-apply state; rollback if tampered
    for (expectedRec in writeSet.records) {
        val actualRec = applied.writeSet.records.find {
            it.entityKind == expectedRec.entityKind && it.entityId == expectedRec.entityId
        }
        if (actualRec == null ||
            actualRec.expectedDigest != expectedRec.expectedDigest ||
            actualRec.newVersion != expectedRec.newVersion
        ) {
            // Rollback state to beforeSnap
            val pageMap = LinkedHashMap<PageId, PageDescriptor>().apply {
                for (p in beforeSnap.pages) put(p.id, p)
            }
            val layerMap = LinkedHashMap<LayerId, LayerDescriptor>().apply {
                for (l in beforeSnap.layers) put(l.id, l)
            }
            val parentGroups = LinkedHashMap<ObjectId, ObjectId>().apply {
                for (obj in beforeSnap.directory.allResidentObjects()) {
                    if (obj.payload is GroupPayload) {
                        for (m in obj.payload.members) put(m, obj.id)
                    }
                }
            }
            state.commitTransition(
                nextVersion = beforeSnap.version,
                nextDirectory = beforeSnap.directory,
                nextPages = beforeSnap.pages,
                nextLayers = beforeSnap.layers,
                nextPageMap = pageMap,
                nextLayerMap = layerMap,
                nextParentGroups = parentGroups
            )
            return Outcome.Failure(
                EditError.InvalidPayload(
                    "WriteSet replay digest/version mismatch for ${expectedRec.entityKind}:${expectedRec.entityId}"
                )
            )
        }
    }

    return Outcome.Success(applied)
}

/**
 * Bounded Undo/Redo history with logical gesture grouping and pinned memory tracking.
 */
class History(
    val maxEntries: Int = 100,
    val maxPinnedBytes: Long = 16_000_000L
) {
    private data class HistoryEntry(
        val gesture: GestureId,
        val label: UndoLabel,
        val forwardMutations: List<Mutation>,
        val inverseMutations: List<Mutation>,
        val estimatedBytes: Long
    )

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var currentPinnedBytes: Long = 0L

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val pinnedBytes: Long get() = currentPinnedBytes

    fun record(plan: EditPlan, applied: AppliedEdit) {
        // Clear redo stack on new user edit
        while (redoStack.isNotEmpty()) {
            val removed = redoStack.removeLast()
            currentPinnedBytes = (currentPinnedBytes - removed.estimatedBytes).coerceAtLeast(0L)
        }

        val entryBytes = estimateMutationsBytes(plan.mutations) + estimateMutationsBytes(applied.inversePlan.mutations)
        val top = undoStack.lastOrNull()
        if (top != null && top.gesture == plan.gesture) {
            // Coalesce consecutive steps of the same logical gesture into one undo entry
            undoStack.removeLast()
            currentPinnedBytes = (currentPinnedBytes - top.estimatedBytes).coerceAtLeast(0L)

            val combinedForward = top.forwardMutations + plan.mutations
            val combinedInverse = applied.inversePlan.mutations + top.inverseMutations
            val combinedBytes = estimateMutationsBytes(combinedForward) + estimateMutationsBytes(combinedInverse)
            val merged = HistoryEntry(
                gesture = top.gesture,
                label = plan.label,
                forwardMutations = combinedForward,
                inverseMutations = combinedInverse,
                estimatedBytes = combinedBytes
            )
            undoStack.addLast(merged)
            currentPinnedBytes += combinedBytes
        } else {
            val entry = HistoryEntry(
                gesture = plan.gesture,
                label = plan.label,
                forwardMutations = plan.mutations,
                inverseMutations = applied.inversePlan.mutations,
                estimatedBytes = entryBytes
            )
            undoStack.addLast(entry)
            currentPinnedBytes += entryBytes
        }

        enforceLimits()
    }

    fun undo(currentVersion: DocVersion): EditPlan? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(entry)
        return EditPlan(
            base = currentVersion,
            mutations = entry.inverseMutations,
            gesture = GestureId("undo-${entry.gesture.value}"),
            label = UndoLabel("Undo: ${entry.label.value}")
        )
    }

    fun redo(currentVersion: DocVersion): EditPlan? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        return EditPlan(
            base = currentVersion,
            mutations = entry.forwardMutations,
            gesture = entry.gesture,
            label = UndoLabel("Redo: ${entry.label.value}")
        )
    }

    private fun enforceLimits() {
        while (undoStack.size > maxEntries || (currentPinnedBytes > maxPinnedBytes && undoStack.size > 1)) {
            val evicted = undoStack.removeFirst()
            currentPinnedBytes = (currentPinnedBytes - evicted.estimatedBytes).coerceAtLeast(0L)
        }
    }

    private fun estimateMutationsBytes(mutations: List<Mutation>): Long {
        var total = 64L
        for (m in mutations) {
            total += when (m) {
                is InsertObject -> 160L + when (val p = m.payload) {
                    is inkspace.core.payload.StrokePayload -> p.samples.size * 24L
                    is inkspace.core.payload.TextPayload -> p.text.length * 2L
                    else -> 64L
                }
                is UpdatePayload -> 128L + when (val p = m.newPayload) {
                    is inkspace.core.payload.StrokePayload -> p.samples.size * 24L
                    is inkspace.core.payload.TextPayload -> p.text.length * 2L
                    else -> 64L
                }
                else -> 64L
            }
        }
        return total
    }
}
