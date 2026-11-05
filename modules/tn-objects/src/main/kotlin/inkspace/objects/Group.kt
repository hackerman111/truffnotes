package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.model.ObjectStyle
import inkspace.core.model.SceneObject
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.Precondition
import inkspace.core.mutation.RemoveObject
import inkspace.core.mutation.UpdatePayload
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.GroupPayload
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocRect

data class ExpandedGroupTargets(
    val leafObjects: List<SceneObject>,
    val bottomUpGroupObjects: List<SceneObject>,
    val bottomUpAncestorGroupsToRebound: List<SceneObject>
)

/**
 * Group hierarchy resolution, single-transform deduplication, and `Group` / `Ungroup` planning.
 */
object GroupHierarchy {

    fun resolveSelectionTarget(
        read: DocumentRead,
        hitId: ObjectId,
        enteredGroupId: ObjectId?
    ): ObjectId? {
        if (enteredGroupId == null) {
            var cur = hitId
            val visited = HashSet<ObjectId>()
            while (visited.add(cur)) {
                val parent = read.parentGroupOf(cur) ?: break
                cur = parent
            }
            return cur
        } else {
            if (hitId == enteredGroupId) return null
            var cur = hitId
            val visited = HashSet<ObjectId>()
            while (visited.add(cur)) {
                val parent = read.parentGroupOf(cur) ?: return null
                if (parent == enteredGroupId) {
                    return cur
                }
                cur = parent
            }
            return null
        }
    }

    /**
     * Expands `rawTargets` into deduplicated leaf objects and bottom-up group objects so every
     * member is transformed at most once, even when both a parent group and its child group/member
     * are present in `rawTargets`. Also collects ancestor groups above `rawTargets` (e.g. during
     * edit-inside-group) whose bounding boxes need to be recomputed.
     */
    fun expandUniqueTreeTargets(
        read: DocumentRead,
        rawTargets: Set<ObjectId>
    ): Outcome<ExpandedGroupTargets, FeatureError> {
        if (rawTargets.isEmpty()) {
            return Outcome.Failure(FeatureError.EmptySelection("Target object set is empty"))
        }

        val notResident = ArrayList<String>()
        val leavesMap = LinkedHashMap<ObjectId, SceneObject>()
        val groupsMap = LinkedHashMap<ObjectId, SceneObject>()
        val visiting = HashSet<ObjectId>()

        fun visit(id: ObjectId): FeatureError? {
            when (read.objectState(id)) {
                ResidencyState.Missing -> return FeatureError.EntityNotFound(id.value)
                ResidencyState.NotResident -> {
                    notResident.add(id.value)
                    return null
                }
                ResidencyState.Present -> {}
            }
            val obj = read.getObject(id) ?: return FeatureError.EntityNotFound(id.value)
            if (obj.isLocked || read.isLocked(id) || read.isLayerLocked(obj.layer)) {
                return FeatureError.LockedTarget(id.value, "Object or its layer is locked")
            }

            val payload = obj.payload
            if (payload is GroupPayload) {
                if (groupsMap.containsKey(id)) return null
                if (!visiting.add(id)) {
                    return FeatureError.GroupCycleDetected(id.value)
                }
                for (memberId in payload.members) {
                    val err = visit(memberId)
                    if (err != null) return err
                }
                visiting.remove(id)
                // Post-order insertion puts child groups before parent groups (bottom-up)
                groupsMap[id] = obj
            } else {
                leavesMap[id] = obj
            }
            return null
        }

        for (targetId in rawTargets) {
            val err = visit(targetId)
            if (err != null) return Outcome.Failure(err)
        }
        if (notResident.isNotEmpty()) {
            return Outcome.Failure(FeatureError.ObjectsNotResident(notResident))
        }

        // Collect any ancestor groups above rawTargets (when editing inside an entered group)
        val ancestorGroups = LinkedHashMap<ObjectId, SceneObject>()
        val visitedAncestors = HashSet<ObjectId>()
        for (targetId in rawTargets) {
            var cur = read.parentGroupOf(targetId)
            while (cur != null && !groupsMap.containsKey(cur) && visitedAncestors.add(cur)) {
                val gObj = read.getObject(cur) ?: break
                if (gObj.isLocked || read.isLocked(cur) || read.isLayerLocked(gObj.layer)) {
                    return Outcome.Failure(
                        FeatureError.LockedTarget(cur.value, "Ancestor group ${cur.value} is locked")
                    )
                }
                ancestorGroups[cur] = gObj
                cur = read.parentGroupOf(cur)
            }
        }

        return Outcome.Success(
            ExpandedGroupTargets(
                leafObjects = leavesMap.values.toList(),
                bottomUpGroupObjects = groupsMap.values.toList(),
                bottomUpAncestorGroupsToRebound = ancestorGroups.values.toList()
            )
        )
    }

    fun computeSubtreeDepth(read: DocumentRead, id: ObjectId, visited: MutableSet<ObjectId> = HashSet()): Int {
        if (!visited.add(id)) return -1
        val obj = read.getObject(id) ?: return 0
        val payload = obj.payload as? GroupPayload ?: return 0
        var maxChild = 0
        for (memberId in payload.members) {
            val d = computeSubtreeDepth(read, memberId, visited)
            if (d < 0) return -1
            if (d > maxChild) maxChild = d
        }
        visited.remove(id)
        return 1 + maxChild
    }

    fun computeAncestorDepth(read: DocumentRead, parentGroupId: ObjectId?): Int {
        var depth = 0
        var cur = parentGroupId
        val visited = HashSet<ObjectId>()
        while (cur != null && visited.add(cur)) {
            depth += 1
            cur = read.parentGroupOf(cur)
        }
        return depth
    }

    fun planGroup(
        read: DocumentRead,
        action: ObjectAction.Group,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val targets = action.frozenTargets
        if (targets.isEmpty()) {
            return Outcome.Failure(FeatureError.EmptySelection("Cannot create an empty group"))
        }
        if (read.objectState(action.newGroupId) != ResidencyState.Missing) {
            return Outcome.Failure(
                FeatureError.InvalidGroupOperation("Group ID ${action.newGroupId.value} already exists")
            )
        }

        // Check residency, locks, same space, and parent ownership
        val memberObjects = ArrayList<SceneObject>(targets.size)
        var maxMemberSubtreeDepth = 0
        val commonParent = read.parentGroupOf(targets.first())

        for (id in targets) {
            if (id == action.newGroupId) {
                return Outcome.Failure(FeatureError.GroupCycleDetected(action.newGroupId.value))
            }
            when (read.objectState(id)) {
                ResidencyState.Missing -> return Outcome.Failure(FeatureError.EntityNotFound(id.value))
                ResidencyState.NotResident -> return Outcome.Failure(FeatureError.ObjectsNotResident(listOf(id.value)))
                ResidencyState.Present -> {}
            }
            val obj = read.getObject(id) ?: return Outcome.Failure(FeatureError.EntityNotFound(id.value))
            if (obj.isLocked || read.isLocked(id) || read.isLayerLocked(obj.layer)) {
                return Outcome.Failure(FeatureError.LockedTarget(id.value, "Cannot group locked entity"))
            }
            if (read.parentGroupOf(id) != commonParent) {
                return Outcome.Failure(
                    FeatureError.InvalidGroupOperation("All grouped targets must share the same parent context")
                )
            }
            val subDepth = computeSubtreeDepth(read, id)
            if (subDepth < 0) {
                return Outcome.Failure(FeatureError.GroupCycleDetected(id.value))
            }
            if (subDepth > maxMemberSubtreeDepth) {
                maxMemberSubtreeDepth = subDepth
            }
            memberObjects.add(obj)
        }

        val ancestorDepth = computeAncestorDepth(read, commonParent)
        val resultingDepth = ancestorDepth + 1 + maxMemberSubtreeDepth
        if (resultingDepth > ctx.limits.maxGroupDepth) {
            return Outcome.Failure(
                FeatureError.MaxGroupDepthExceeded(
                    current = resultingDepth,
                    max = ctx.limits.maxGroupDepth
                )
            )
        }

        val first = memberObjects.first()
        for (obj in memberObjects) {
            if (obj.space != first.space) {
                return Outcome.Failure(
                    FeatureError.InvalidGroupOperation("Cannot group objects from different spaces")
                )
            }
        }

        var unionBounds: DocRect = first.bounds
        var maxZ = first.style.zOrder
        for (obj in memberObjects) {
            unionBounds = unionBounds.union(obj.bounds)
            if (obj.style.zOrder > maxZ) maxZ = obj.style.zOrder
        }

        val orderedMemberIds = memberObjects.map { it.id }
        val preconditions = memberObjects.map { Precondition(it.id.value, it.version) }.toMutableList()
        val mutations = ArrayList<Mutation>()

        // If grouping inside an existing parent group, first update the parent group to replace targets with newGroupId
        if (commonParent != null) {
            val parentObj = read.getObject(commonParent)
                ?: return Outcome.Failure(FeatureError.EntityNotFound(commonParent.value))
            val parentPayload = parentObj.payload as? GroupPayload
                ?: return Outcome.Failure(FeatureError.InvalidGroupOperation("Parent is not a group"))
            preconditions.add(Precondition(parentObj.id.value, parentObj.version))

            val updatedParentMembers = ArrayList<ObjectId>()
            var insertedNewGroup = false
            for (m in parentPayload.members) {
                if (m in targets) {
                    if (!insertedNewGroup) {
                        updatedParentMembers.add(action.newGroupId)
                        insertedNewGroup = true
                    }
                } else {
                    updatedParentMembers.add(m)
                }
            }
            mutations.add(
                InsertObject(
                    space = first.space,
                    layer = first.layer,
                    id = action.newGroupId,
                    payload = GroupPayload(orderedMemberIds),
                    bounds = unionBounds,
                    style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = maxZ)
                )
            )
            mutations.add(
                UpdatePayload(
                    id = parentObj.id,
                    newPayload = GroupPayload(updatedParentMembers),
                    newBounds = parentObj.bounds
                )
            )
        } else {
            mutations.add(
                InsertObject(
                    space = first.space,
                    layer = first.layer,
                    id = action.newGroupId,
                    payload = GroupPayload(orderedMemberIds),
                    bounds = unionBounds,
                    style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = maxZ)
                )
            )
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Group Objects")
            )
        )
    }

    fun planUngroup(
        read: DocumentRead,
        action: ObjectAction.Ungroup,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val groupIds = action.frozenGroupIds
        if (groupIds.isEmpty()) {
            return Outcome.Failure(FeatureError.EmptySelection("No groups specified to ungroup"))
        }

        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()

        for (groupId in groupIds) {
            when (read.objectState(groupId)) {
                ResidencyState.Missing -> return Outcome.Failure(FeatureError.EntityNotFound(groupId.value))
                ResidencyState.NotResident -> {
                    return Outcome.Failure(FeatureError.ObjectsNotResident(listOf(groupId.value)))
                }
                ResidencyState.Present -> {}
            }
            val groupObj = read.getObject(groupId)
                ?: return Outcome.Failure(FeatureError.EntityNotFound(groupId.value))
            if (groupObj.isLocked || read.isLocked(groupId) || read.isLayerLocked(groupObj.layer)) {
                return Outcome.Failure(FeatureError.LockedTarget(groupId.value, "Cannot ungroup locked group"))
            }
            val groupPayload = groupObj.payload as? GroupPayload
                ?: return Outcome.Failure(
                    FeatureError.InvalidGroupOperation("Object ${groupId.value} is not a group")
                )

            preconditions.add(Precondition(groupObj.id.value, groupObj.version))

            val parentId = read.parentGroupOf(groupId)
            if (parentId != null && parentId !in groupIds) {
                val parentObj = read.getObject(parentId)
                    ?: return Outcome.Failure(FeatureError.EntityNotFound(parentId.value))
                val parentPayload = parentObj.payload as? GroupPayload
                    ?: return Outcome.Failure(FeatureError.InvalidGroupOperation("Parent is not a group"))
                preconditions.add(Precondition(parentObj.id.value, parentObj.version))
                val splicedMembers = ArrayList<ObjectId>()
                for (m in parentPayload.members) {
                    if (m == groupId) {
                        splicedMembers.addAll(groupPayload.members)
                    } else {
                        splicedMembers.add(m)
                    }
                }
                mutations.add(
                    UpdatePayload(
                        id = parentObj.id,
                        newPayload = GroupPayload(splicedMembers),
                        newBounds = parentObj.bounds
                    )
                )
            }
            mutations.add(RemoveObject(groupId))
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Ungroup Objects")
            )
        )
    }
}
