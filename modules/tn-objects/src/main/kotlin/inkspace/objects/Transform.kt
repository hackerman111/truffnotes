package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.SceneObject
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.MoveObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.Precondition
import inkspace.core.mutation.RemoveObject
import inkspace.core.mutation.TransformObject
import inkspace.core.mutation.UpdateObjectStyle
import inkspace.core.mutation.UpdatePayload
import inkspace.core.objects.FlipAxis
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.PlanContext
import inkspace.core.objects.PreviewTransformOverlay
import inkspace.core.objects.ZOrderCommand
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.StickyPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.TapePayload
import inkspace.core.payload.TextPayload
import inkspace.core.read.DocumentRead
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Planners for move, scale, rotate, flip, style updates, z-order, cross-layer transfer,
 * and non-mutating live preview overlays.
 */
object TransformPlanner {

    fun buildPreview(
        read: DocumentRead,
        targets: Set<ObjectId>,
        deltaTransform: AffineTransform2D
    ): Outcome<PreviewTransformOverlay, FeatureError> {
        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, targets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }
        var unionBounds: DocRect? = null
        for (leaf in expanded.leafObjects) {
            val nextTransform = deltaTransform.multiply(leaf.transform)
            val b = computeTransformedLeafBounds(leaf, nextTransform, deltaTransform)
            unionBounds = if (unionBounds == null) b else unionBounds.union(b)
        }
        val finalBounds = unionBounds ?: DocRect(0.0, 0.0, 0.0, 0.0)
        return Outcome.Success(
            PreviewTransformOverlay(
                targetIds = targets,
                deltaTransform = deltaTransform,
                previewBounds = finalBounds
            )
        )
    }

    fun planMove(
        read: DocumentRead,
        action: ObjectAction.Move,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        if (!action.deltaX.isFinite() || !action.deltaY.isFinite()) {
            return Outcome.Failure(FeatureError.InvalidParameters("Move deltas must be finite"))
        }
        if (action.deltaX == 0.0 && action.deltaY == 0.0) {
            return Outcome.Failure(FeatureError.NoOpAction("Zero move delta"))
        }

        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }

        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()
        val updatedBoundsMap = HashMap<ObjectId, DocRect>()

        // 1. Move each unique leaf object exactly once
        for (leaf in expanded.leafObjects) {
            preconditions.add(Precondition(leaf.id.value, leaf.version))
            val nextBounds = try {
                leaf.bounds.translate(action.deltaX, action.deltaY)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Bounds out of envelope"))
            }
            updatedBoundsMap[leaf.id] = nextBounds
            mutations.add(
                MoveObject(
                    id = leaf.id,
                    deltaX = action.deltaX,
                    deltaY = action.deltaY
                )
            )
        }

        // 2. Move each unique selected/descendant group object once
        for (grp in expanded.bottomUpGroupObjects) {
            preconditions.add(Precondition(grp.id.value, grp.version))
            val nextBounds = try {
                grp.bounds.translate(action.deltaX, action.deltaY)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Group bounds out of envelope"))
            }
            updatedBoundsMap[grp.id] = nextBounds
            mutations.add(
                MoveObject(
                    id = grp.id,
                    deltaX = action.deltaX,
                    deltaY = action.deltaY
                )
            )
        }

        // 3. Recompute bounding boxes of any ancestor groups above an edited member (edit-inside-group)
        for (anc in expanded.bottomUpAncestorGroupsToRebound) {
            val gPayload = anc.payload as? GroupPayload ?: continue
            var union: DocRect? = null
            for (mId in gPayload.members) {
                val mBounds = updatedBoundsMap[mId] ?: read.getObject(mId)?.bounds ?: continue
                union = if (union == null) mBounds else union.union(mBounds)
            }
            if (union != null && union != anc.bounds) {
                preconditions.add(Precondition(anc.id.value, anc.version))
                updatedBoundsMap[anc.id] = union
                mutations.add(
                    TransformObject(
                        id = anc.id,
                        newTransform = anc.transform,
                        newBounds = union
                    )
                )
            }
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Move Objects")
            )
        )
    }

    fun planScale(
        read: DocumentRead,
        action: ObjectAction.Scale,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        if (!action.scaleX.isFinite() || !action.scaleY.isFinite() ||
            abs(action.scaleX) < 1e-4 || abs(action.scaleY) < 1e-4
        ) {
            return Outcome.Failure(
                FeatureError.InvalidParameters("Scale factors must be finite and non-zero")
            )
        }
        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }
        val pivot = action.pivot ?: computeDefaultPivot(expanded.leafObjects)
        val delta = AffineTransform2D.scaleAround(action.scaleX, action.scaleY, pivot)
        return planAffineMatrix(read, expanded, delta, ctx, UndoLabel("Scale Objects"))
    }

    fun planRotate(
        read: DocumentRead,
        action: ObjectAction.Rotate,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        if (!action.angleRadians.isFinite()) {
            return Outcome.Failure(FeatureError.InvalidParameters("Rotation angle must be finite"))
        }
        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }
        val pivot = action.pivot ?: computeDefaultPivot(expanded.leafObjects)
        val delta = AffineTransform2D.rotateAround(action.angleRadians, pivot)
        return planAffineMatrix(read, expanded, delta, ctx, UndoLabel("Rotate Objects"))
    }

    fun planFlip(
        read: DocumentRead,
        action: ObjectAction.Flip,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }
        val pivot = action.pivot ?: computeDefaultPivot(expanded.leafObjects)
        val delta = when (action.axis) {
            FlipAxis.HORIZONTAL -> AffineTransform2D.flipHorizontalAround(pivot.x)
            FlipAxis.VERTICAL -> AffineTransform2D.flipVerticalAround(pivot.y)
        }
        return planAffineMatrix(read, expanded, delta, ctx, UndoLabel("Flip Objects"))
    }

    private fun planAffineMatrix(
        read: DocumentRead,
        expanded: ExpandedGroupTargets,
        delta: AffineTransform2D,
        ctx: PlanContext,
        label: UndoLabel
    ): Outcome<EditPlan, FeatureError> {
        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()
        val updatedBoundsMap = HashMap<ObjectId, DocRect>()

        for (leaf in expanded.leafObjects) {
            preconditions.add(Precondition(leaf.id.value, leaf.version))
            val nextTransform = try {
                delta.multiply(leaf.transform)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Invalid transform"))
            }
            val nextBounds = try {
                computeTransformedLeafBounds(leaf, nextTransform, delta)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Bounds out of envelope"))
            }
            updatedBoundsMap[leaf.id] = nextBounds
            mutations.add(
                TransformObject(
                    id = leaf.id,
                    newTransform = nextTransform,
                    newBounds = nextBounds
                )
            )
        }

        for (grp in expanded.bottomUpGroupObjects) {
            val gPayload = grp.payload as? GroupPayload ?: continue
            preconditions.add(Precondition(grp.id.value, grp.version))
            var union: DocRect? = null
            for (mId in gPayload.members) {
                val mBounds = updatedBoundsMap[mId] ?: read.getObject(mId)?.bounds ?: continue
                union = if (union == null) mBounds else union.union(mBounds)
            }
            val nextBounds = union ?: delta.transformRect(grp.bounds)
            val nextTransform = delta.multiply(grp.transform)
            updatedBoundsMap[grp.id] = nextBounds
            mutations.add(
                TransformObject(
                    id = grp.id,
                    newTransform = nextTransform,
                    newBounds = nextBounds
                )
            )
        }

        for (anc in expanded.bottomUpAncestorGroupsToRebound) {
            val gPayload = anc.payload as? GroupPayload ?: continue
            var union: DocRect? = null
            for (mId in gPayload.members) {
                val mBounds = updatedBoundsMap[mId] ?: read.getObject(mId)?.bounds ?: continue
                union = if (union == null) mBounds else union.union(mBounds)
            }
            if (union != null && union != anc.bounds) {
                preconditions.add(Precondition(anc.id.value, anc.version))
                updatedBoundsMap[anc.id] = union
                mutations.add(
                    TransformObject(
                        id = anc.id,
                        newTransform = anc.transform,
                        newBounds = union
                    )
                )
            }
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = label
            )
        )
    }

    fun planApplyStyle(
        read: DocumentRead,
        action: ObjectAction.ApplyStyle,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val patch = action.patch
        if (patch.colorArgb == null && patch.strokeWidthPt == null && patch.opacity == null) {
            return Outcome.Failure(FeatureError.NoOpAction("StylePatch has no fields set"))
        }

        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }

        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()

        for (leaf in expanded.leafObjects) {
            preconditions.add(Precondition(leaf.id.value, leaf.version))
            var curVersion = leaf.version

            val patchOpacity = patch.opacity
            if (patchOpacity != null && patchOpacity != leaf.style.opacity) {
                curVersion += 1L
                mutations.add(
                    UpdateObjectStyle(
                        id = leaf.id,
                        newStyle = leaf.style.copy(opacity = patchOpacity),
                        targetVersion = curVersion
                    )
                )
            }

            val updatedPayload = when (val p = leaf.payload) {
                is StrokePayload -> p.copy(
                    color = patch.colorArgb ?: p.color,
                    baseWidthPt = patch.strokeWidthPt ?: p.baseWidthPt
                )
                is ShapePayload -> p.copy(
                    strokeColor = patch.colorArgb ?: p.strokeColor,
                    strokeWidthPt = patch.strokeWidthPt ?: p.strokeWidthPt
                )
                is ConnectorPayload -> p.copy(
                    strokeColor = patch.colorArgb ?: p.strokeColor,
                    strokeWidthPt = patch.strokeWidthPt ?: p.strokeWidthPt
                )
                is TextPayload -> p.copy(
                    fontColor = patch.colorArgb ?: p.fontColor,
                    fontSizePt = patch.strokeWidthPt ?: p.fontSizePt
                )
                is StickyPayload -> p.copy(
                    color = patch.colorArgb ?: p.color
                )
                is TapePayload -> p.copy(
                    patternColor = patch.colorArgb ?: p.patternColor,
                    opacity = patch.opacity ?: p.opacity
                )
                else -> p
            }

            if (updatedPayload != leaf.payload) {
                curVersion += 1L
                mutations.add(
                    UpdatePayload(
                        id = leaf.id,
                        newPayload = updatedPayload,
                        newBounds = leaf.bounds,
                        targetVersion = curVersion
                    )
                )
            }
        }

        if (mutations.isEmpty()) {
            return Outcome.Failure(FeatureError.NoOpAction("StylePatch resulted in no changes"))
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Update Object Style")
            )
        )
    }

    fun planChangeZOrder(
        read: DocumentRead,
        action: ObjectAction.ChangeZOrder,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }

        val allTargetObjects = expanded.leafObjects + expanded.bottomUpGroupObjects
        if (allTargetObjects.isEmpty()) {
            return Outcome.Failure(FeatureError.EmptySelection("No objects for z-order change"))
        }

        // Compute existing min/max zOrder in the active space
        val spaceBounds = DocRect(-1e8, -1e8, 1e8, 1e8)
        val allIds = read.queryBounds(spaceBounds)
        var minZ = 0L
        var maxZ = 0L
        var anySeen = false
        for (id in allIds) {
            val obj = read.getObject(id) ?: continue
            if (!anySeen) {
                minZ = obj.style.zOrder
                maxZ = obj.style.zOrder
                anySeen = true
            } else {
                if (obj.style.zOrder < minZ) minZ = obj.style.zOrder
                if (obj.style.zOrder > maxZ) maxZ = obj.style.zOrder
            }
        }

        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()
        val sortedTargets = allTargetObjects.sortedBy { it.style.zOrder }

        for ((idx, obj) in sortedTargets.withIndex()) {
            preconditions.add(Precondition(obj.id.value, obj.version))
            val nextZ = when (action.command) {
                ZOrderCommand.BRING_TO_FRONT -> maxZ + 1L + idx
                ZOrderCommand.SEND_TO_BACK -> minZ - sortedTargets.size.toLong() + idx
                ZOrderCommand.BRING_FORWARD -> obj.style.zOrder + 1L
                ZOrderCommand.SEND_BACKWARD -> obj.style.zOrder - 1L
            }
            mutations.add(
                UpdateObjectStyle(
                    id = obj.id,
                    newStyle = obj.style.copy(zOrder = nextZ)
                )
            )
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Change Z-Order")
            )
        )
    }

    fun planMoveToLayer(
        read: DocumentRead,
        action: ObjectAction.MoveToLayer,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val targetLayer = read.getLayer(action.targetLayerId)
        if (read.layers.isNotEmpty() && targetLayer == null) {
            return Outcome.Failure(FeatureError.EntityNotFound("Target layer ${action.targetLayerId.value} not found"))
        }
        if (targetLayer?.isLocked == true || read.isLayerLocked(action.targetLayerId)) {
            return Outcome.Failure(
                FeatureError.LockedTarget(action.targetLayerId.value, "Target layer is locked")
            )
        }

        val expanded = when (val out = GroupHierarchy.expandUniqueTreeTargets(read, action.frozenTargets)) {
            is Outcome.Failure -> return out
            is Outcome.Success -> out.value
        }

        val allToMove = expanded.leafObjects + expanded.bottomUpGroupObjects
        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()

        // First remove groups top-down so group membership validation during staging stays consistent,
        // then remove leaves, then re-insert leaves on targetLayerId, then re-insert groups bottom-up.
        val topDownGroups = expanded.bottomUpGroupObjects.asReversed()
        for (grp in topDownGroups) {
            if (grp.layer == action.targetLayerId) continue
            preconditions.add(Precondition(grp.id.value, grp.version))
            mutations.add(RemoveObject(grp.id))
        }
        for (leaf in expanded.leafObjects) {
            if (leaf.layer == action.targetLayerId) continue
            preconditions.add(Precondition(leaf.id.value, leaf.version))
            mutations.add(RemoveObject(leaf.id))
        }
        for (leaf in expanded.leafObjects) {
            if (leaf.layer == action.targetLayerId) continue
            mutations.add(
                InsertObject(
                    space = leaf.space,
                    layer = action.targetLayerId,
                    id = leaf.id,
                    payload = leaf.payload,
                    bounds = leaf.bounds,
                    transform = leaf.transform,
                    style = leaf.style,
                    isLocked = leaf.isLocked,
                    targetVersion = leaf.version + 1L
                )
            )
        }
        for (grp in expanded.bottomUpGroupObjects) {
            if (grp.layer == action.targetLayerId) continue
            mutations.add(
                InsertObject(
                    space = grp.space,
                    layer = action.targetLayerId,
                    id = grp.id,
                    payload = grp.payload,
                    bounds = grp.bounds,
                    transform = grp.transform,
                    style = grp.style,
                    isLocked = grp.isLocked,
                    targetVersion = grp.version + 1L
                )
            )
        }

        if (mutations.isEmpty()) {
            return Outcome.Failure(FeatureError.NoOpAction("Objects are already on layer ${action.targetLayerId.value}"))
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Move Objects To Layer")
            )
        )
    }

    private fun computeDefaultPivot(leaves: List<SceneObject>): DocPoint {
        if (leaves.isEmpty()) return DocPoint(0.0, 0.0)
        var union = leaves.first().bounds
        for (i in 1 until leaves.size) {
            union = union.union(leaves[i].bounds)
        }
        return DocPoint(
            x = (union.left + union.right) * 0.5,
            y = (union.top + union.bottom) * 0.5
        )
    }

    fun computeTransformedLeafBounds(
        leaf: SceneObject,
        newTransform: AffineTransform2D,
        delta: AffineTransform2D
    ): DocRect {
        val payload = leaf.payload
        if (payload is StrokePayload && payload.samples.isNotEmpty()) {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (s in payload.samples) {
                val localPt = DocPoint(payload.origin.x + s.relX, payload.origin.y + s.relY)
                val worldPt = newTransform.transformPoint(localPt)
                if (worldPt.x < minX) minX = worldPt.x
                if (worldPt.y < minY) minY = worldPt.y
                if (worldPt.x > maxX) maxX = worldPt.x
                if (worldPt.y > maxY) maxY = worldPt.y
            }
            val scaleApprox = sqrt(
                max(
                    newTransform.m00 * newTransform.m00 + newTransform.m10 * newTransform.m10,
                    newTransform.m01 * newTransform.m01 + newTransform.m11 * newTransform.m11
                )
            ).coerceAtLeast(1.0)
            val pad = payload.baseWidthPt * scaleApprox
            return DocRect(
                min(minX - pad, maxX + pad),
                min(minY - pad, maxY + pad),
                max(minX - pad, maxX + pad),
                max(minY - pad, maxY + pad)
            )
        }
        return delta.transformRect(leaf.bounds)
    }
}
