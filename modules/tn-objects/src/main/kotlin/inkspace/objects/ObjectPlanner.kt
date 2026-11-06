package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.Precondition
import inkspace.core.mutation.RemoveObject
import inkspace.core.mutation.UpdatePayload
import inkspace.core.objects.ClipboardBundle
import inkspace.core.objects.ClipboardObjectEntry
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.PlanContext
import inkspace.core.objects.TransferId
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.GroupPayload
import inkspace.core.read.DocumentRead
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect

/**
 * Primary entry point for `:tn-objects`:
 * `ObjectPlanner.plan(read: DocumentRead, action: ObjectAction, ctx: PlanContext): Outcome<EditPlan, FeatureError>`
 * plus clipboard bundle creation (`copyToClipboard`, `cutToClipboard`).
 */
object ObjectPlanner {

    fun plan(
        read: DocumentRead,
        action: ObjectAction,
        ctx: PlanContext = PlanContext()
    ): Outcome<EditPlan, FeatureError> {
        return when (action) {
            is ObjectAction.Move -> TransformPlanner.planMove(read, action, ctx)
            is ObjectAction.Scale -> TransformPlanner.planScale(read, action, ctx)
            is ObjectAction.Rotate -> TransformPlanner.planRotate(read, action, ctx)
            is ObjectAction.Flip -> TransformPlanner.planFlip(read, action, ctx)
            is ObjectAction.ApplyStyle -> TransformPlanner.planApplyStyle(read, action, ctx)
            is ObjectAction.ChangeZOrder -> TransformPlanner.planChangeZOrder(read, action, ctx)
            is ObjectAction.MoveToLayer -> TransformPlanner.planMoveToLayer(read, action, ctx)
            is ObjectAction.Group -> GroupHierarchy.planGroup(read, action, ctx)
            is ObjectAction.Ungroup -> GroupHierarchy.planUngroup(read, action, ctx)
            is ObjectAction.Erase -> ErasePlanner.planErase(read, action, ctx)
            is ObjectAction.Delete -> planDelete(read, action, ctx)
            is ObjectAction.Duplicate -> planDuplicate(read, action, ctx)
            is ObjectAction.Paste -> planPaste(read, action, ctx)
            is ObjectAction.ShapeAction -> ShapePlanner.plan(read, action, ctx)
        }
    }

    fun copyToClipboard(
        read: DocumentRead,
        targets: Set<ObjectId>,
        transferId: TransferId = TransferId("transfer-1")
    ): Outcome<ClipboardBundle, FeatureError> {
        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, targets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }

        // Order entries so leaf objects come first, followed by groups bottom-up
        val orderedObjects = expanded.leafObjects + expanded.bottomUpGroupObjects
        if (orderedObjects.isEmpty()) {
            return Outcome.Failure(FeatureError.EmptySelection("Cannot copy empty selection"))
        }

        var unionBounds: DocRect = orderedObjects.first().bounds
        for (obj in orderedObjects) {
            unionBounds = unionBounds.union(obj.bounds)
        }

        val includedGroupIds = expanded.bottomUpGroupObjects.map { it.id }.toSet()
        val entries = orderedObjects.map { obj ->
            val parentId = read.parentGroupOf(obj.id)?.takeIf { it in includedGroupIds }
            ClipboardObjectEntry(
                originalId = obj.id,
                objectSnapshot = obj.frozen(),
                parentGroupId = parentId
            )
        }

        return Outcome.Success(
            ClipboardBundle(
                transferId = transferId,
                sourceDocument = read.version.document,
                sourceSpace = orderedObjects.first().space,
                sourceBounds = unionBounds,
                entries = entries
            )
        )
    }

    fun cutToClipboard(
        read: DocumentRead,
        targets: Set<ObjectId>,
        ctx: PlanContext = PlanContext(),
        transferId: TransferId = TransferId("transfer-cut-1")
    ): Outcome<Pair<ClipboardBundle, EditPlan>, FeatureError> {
        val bundle = when (val out = copyToClipboard(read, targets, transferId)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }
        val deletePlan = when (val out = planDelete(read, ObjectAction.Delete(targets), ctx)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }
        return Outcome.Success(bundle to deletePlan)
    }

    private fun planDelete(
        read: DocumentRead,
        action: ObjectAction.Delete,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }

        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()
        val removedIds = (expanded.leafObjects.map { it.id } + expanded.bottomUpGroupObjects.map { it.id }).toSet()

        // Update any ancestor groups above the deleted targets first
        for (anc in expanded.bottomUpAncestorGroupsToRebound) {
            val gPayload = anc.payload as? GroupPayload ?: continue
            val remainingMembers = gPayload.members.filter { it !in removedIds }
            preconditions.add(Precondition(anc.id.value, anc.version))
            if (remainingMembers.isEmpty()) {
                mutations.add(RemoveObject(anc.id))
            } else {
                mutations.add(
                    UpdatePayload(
                        id = anc.id,
                        newPayload = GroupPayload(remainingMembers),
                        newBounds = anc.bounds
                    )
                )
            }
        }

        // Remove selected/descendant groups top-down, then remove leaf objects
        for (grp in expanded.bottomUpGroupObjects.asReversed()) {
            preconditions.add(Precondition(grp.id.value, grp.version))
            mutations.add(RemoveObject(grp.id))
        }
        for (leaf in expanded.leafObjects) {
            preconditions.add(Precondition(leaf.id.value, leaf.version))
            mutations.add(RemoveObject(leaf.id))
        }

        // Handle connectors connected to deleted targets according to their dangling policy
        val allSpaceIds = read.queryBounds(DocRect(-1e8, -1e8, 1e8, 1e8))
        for (id in allSpaceIds) {
            if (id in removedIds) continue
            val obj = read.getObject(id) ?: continue
            val cPayload = obj.payload as? ConnectorPayload ?: continue
            val srcDeleted = cPayload.sourceId in removedIds
            val dstDeleted = cPayload.targetId in removedIds
            if (srcDeleted || dstDeleted) {
                preconditions.add(Precondition(obj.id.value, obj.version))
                if (cPayload.danglingPolicy == inkspace.core.payload.DanglingEndpointPolicy.DELETE_CONNECTOR) {
                    mutations.add(RemoveObject(obj.id))
                } else {
                    val nextPayload = cPayload.copy(
                        sourceAnchor = if (srcDeleted) {
                            inkspace.core.payload.ConnectorAnchor(side = inkspace.core.payload.AnchorSide.CUSTOM)
                        } else {
                            cPayload.sourceAnchor
                        },
                        targetAnchor = if (dstDeleted) {
                            inkspace.core.payload.ConnectorAnchor(side = inkspace.core.payload.AnchorSide.CUSTOM)
                        } else {
                            cPayload.targetAnchor
                        }
                    )
                    mutations.add(
                        UpdatePayload(
                            id = obj.id,
                            newPayload = nextPayload,
                            newBounds = obj.bounds,
                            targetVersion = obj.version + 1L
                        )
                    )
                }
            }
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Delete Objects")
            )
        )
    }

    private fun planDuplicate(
        read: DocumentRead,
        action: ObjectAction.Duplicate,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        if (!action.offsetX.isFinite() || !action.offsetY.isFinite()) {
            return Outcome.Failure(FeatureError.InvalidParameters("Duplicate offsets must be finite"))
        }
        val bundle = when (val out = copyToClipboard(read, action.frozenTargets, TransferId("dup-${ctx.gestureId.value}"))) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }
        val srcCenter = DocPoint(
            x = (bundle.sourceBounds.left + bundle.sourceBounds.right) * 0.5,
            y = (bundle.sourceBounds.top + bundle.sourceBounds.bottom) * 0.5
        )
        val targetCenter = DocPoint(srcCenter.x + action.offsetX, srcCenter.y + action.offsetY)
        return planPasteInternal(
            read = read,
            bundle = bundle,
            targetCenter = targetCenter,
            targetLayerId = null,
            idPrefix = action.idPrefix,
            ctx = ctx,
            label = UndoLabel("Duplicate Objects")
        )
    }

    private fun planPaste(
        read: DocumentRead,
        action: ObjectAction.Paste,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        return planPasteInternal(
            read = read,
            bundle = action.bundle,
            targetCenter = action.targetCenter,
            targetLayerId = action.targetLayerId,
            idPrefix = action.idPrefix,
            ctx = ctx,
            label = UndoLabel("Paste Objects")
        )
    }

    private fun planPasteInternal(
        read: DocumentRead,
        bundle: ClipboardBundle,
        targetCenter: DocPoint?,
        targetLayerId: inkspace.core.id.LayerId?,
        idPrefix: String,
        ctx: PlanContext,
        label: UndoLabel
    ): Outcome<EditPlan, FeatureError> {
        val entries = bundle.frozenEntries
        if (entries.isEmpty()) {
            return Outcome.Failure(FeatureError.EmptySelection("ClipboardBundle is empty"))
        }

        val destLayer = targetLayerId ?: ctx.activeLayerId
        if (read.isLayerLocked(destLayer)) {
            return Outcome.Failure(FeatureError.LockedTarget(destLayer.value, "Target paste layer is locked"))
        }

        val srcCenter = DocPoint(
            x = (bundle.sourceBounds.left + bundle.sourceBounds.right) * 0.5,
            y = (bundle.sourceBounds.top + bundle.sourceBounds.bottom) * 0.5
        )
        val dx = if (targetCenter != null) targetCenter.x - srcCenter.x else 0.0
        val dy = if (targetCenter != null) targetCenter.y - srcCenter.y else 0.0
        if (!dx.isFinite() || !dy.isFinite()) {
            return Outcome.Failure(FeatureError.InvalidParameters("Non-finite paste offset"))
        }

        // Build deterministic ID remap table
        val idMap = LinkedHashMap<ObjectId, ObjectId>(entries.size)
        for ((idx, entry) in entries.withIndex()) {
            var candidate = ObjectId("$idPrefix-${idx + 1}-${entry.originalId.value}")
            var suffix = 1
            while (read.objectState(candidate) != inkspace.core.read.ResidencyState.Missing || idMap.containsValue(candidate)) {
                suffix += 1
                candidate = ObjectId("$idPrefix-${idx + 1}-$suffix-${entry.originalId.value}")
            }
            idMap[entry.originalId] = candidate
        }

        val mutations = ArrayList<Mutation>(entries.size)
        for (entry in entries) {
            val orig = entry.frozenObject
            val newId = idMap[entry.originalId] ?: continue
            val remappedPayload = when (val p = orig.payload) {
                is GroupPayload -> GroupPayload(
                    members = p.members.map { m -> idMap[m] ?: m }
                )
                is ConnectorPayload -> p.copy(
                    sourceId = idMap[p.sourceId] ?: p.sourceId,
                    targetId = idMap[p.targetId] ?: p.targetId
                )
                else -> p
            }
            val nextBounds = try {
                orig.bounds.translate(dx, dy)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Pasted bounds out of envelope"))
            }
            val nextTransform = orig.transform.translate(dx, dy)
            val resolvedLayer = if (targetLayerId != null) {
                targetLayerId
            } else if (read.layers.isEmpty() || read.getLayer(orig.layer) != null) {
                orig.layer
            } else {
                ctx.activeLayerId
            }
            if (read.isLayerLocked(resolvedLayer)) {
                return Outcome.Failure(FeatureError.LockedTarget(resolvedLayer.value, "Target layer is locked"))
            }

            mutations.add(
                InsertObject(
                    space = ctx.activeSpaceId,
                    layer = resolvedLayer,
                    id = newId,
                    payload = remappedPayload,
                    bounds = nextBounds,
                    transform = nextTransform,
                    style = orig.style,
                    isLocked = false,
                    targetVersion = 1L
                )
            )
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = emptyList(),
                mutations = mutations,
                gesture = ctx.gestureId,
                label = label
            )
        )
    }
}
