package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.ObjectId
import inkspace.core.objects.ObjectKindFilter
import inkspace.core.objects.SelectionCombinator
import inkspace.core.objects.SelectionMode
import inkspace.core.objects.SelectionQuery
import inkspace.core.objects.SelectionSet
import inkspace.core.outcome.Outcome
import inkspace.core.ports.GeometryPort
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocRect

/**
 * Spatial-first selection engine supporting rectangular and freehand lasso (`CONTAINS` / `INTERSECTS`),
 * `REPLACE` / `ADD` / `SUBTRACT` combinators, layer/type filters, and edit-inside-group resolution.
 */
object SelectionEngine {

    fun select(
        read: DocumentRead,
        current: SelectionSet = SelectionSet(),
        query: SelectionQuery,
        geometryPort: GeometryPort? = null
    ): Outcome<SelectionSet, FeatureError> {
        val searchRect: DocRect = when (query.mode) {
            SelectionMode.RECTANGLE -> {
                query.rect ?: return Outcome.Failure(
                    FeatureError.InvalidParameters("RECTANGLE selection requires non-null rect")
                )
            }
            SelectionMode.FREEHAND -> {
                val poly = query.frozenPolygonPoints
                if (poly.size < 3) {
                    return Outcome.Failure(
                        FeatureError.InvalidParameters("FREEHAND selection requires at least 3 polygon points")
                    )
                }
                val minX = poly.minOf { it.x }
                val minY = poly.minOf { it.y }
                val maxX = poly.maxOf { it.x }
                val maxY = poly.maxOf { it.y }
                DocRect(minX, minY, maxX, maxY)
            }
        }

        // Stage 1: Spatial directory bounds query (no full-document scan)
        val candidateIds = read.queryBounds(searchRect)

        // Check for NotResident candidates before planning/selecting
        val notResident = ArrayList<String>()
        for (id in candidateIds) {
            if (read.objectState(id) == ResidencyState.NotResident) {
                notResident.add(id.value)
            }
        }
        if (notResident.isNotEmpty()) {
            return Outcome.Failure(FeatureError.ObjectsNotResident(notResident))
        }

        val matchedTargets = LinkedHashSet<ObjectId>()

        for (id in candidateIds) {
            val obj = read.getObject(id) ?: continue
            if (obj.space != query.spaceId) continue
            if (!isSelectableEntity(read, obj, query)) continue

            // Stage 2: Precise geometric hit-test
            val hits = when (query.mode) {
                SelectionMode.RECTANGLE -> HitTest.matchesRectRule(
                    obj = obj,
                    rect = searchRect,
                    rule = query.rule,
                    read = read,
                    geometryPort = geometryPort
                )
                SelectionMode.FREEHAND -> HitTest.matchesPolygonRule(
                    obj = obj,
                    polygon = query.frozenPolygonPoints,
                    polygonBounds = searchRect,
                    rule = query.rule,
                    read = read,
                    geometryPort = geometryPort
                )
            }
            if (!hits) continue

            val resolvedTarget = GroupHierarchy.resolveSelectionTarget(
                read = read,
                hitId = obj.id,
                enteredGroupId = query.enteredGroupId
            ) ?: continue

            val targetObj = read.getObject(resolvedTarget) ?: continue
            if (!isSelectableEntity(read, targetObj, query)) continue
            matchedTargets.add(resolvedTarget)
        }

        val combinedIds = LinkedHashSet<ObjectId>()
        when (query.combinator) {
            SelectionCombinator.REPLACE -> {
                combinedIds.addAll(matchedTargets)
            }
            SelectionCombinator.ADD -> {
                combinedIds.addAll(current.frozenSelectedIds)
                combinedIds.addAll(matchedTargets)
            }
            SelectionCombinator.SUBTRACT -> {
                combinedIds.addAll(current.frozenSelectedIds)
                combinedIds.removeAll(matchedTargets)
            }
        }

        val unionBounds = computeSelectionBounds(read, combinedIds)
        return Outcome.Success(
            SelectionSet(
                selectedIds = combinedIds,
                enteredGroupId = query.enteredGroupId,
                bounds = unionBounds
            )
        )
    }

    fun computeSelectionBounds(read: DocumentRead, ids: Set<ObjectId>): DocRect? {
        var union: DocRect? = null
        for (id in ids) {
            val obj = read.getObject(id) ?: continue
            union = if (union == null) obj.bounds else union.union(obj.bounds)
        }
        return union
    }

    private fun isSelectableEntity(
        read: DocumentRead,
        obj: inkspace.core.model.SceneObject,
        query: SelectionQuery
    ): Boolean {
        if (obj.isLocked || read.isLocked(obj.id)) return false
        if (!obj.style.isVisible) return false

        val layer = read.getLayer(obj.layer)
        if (layer != null) {
            if (!layer.isVisible || layer.isLocked) return false
        } else if (read.isLayerLocked(obj.layer)) {
            return false
        }

        if (query.filter.activeLayerOnly && query.filter.activeLayerId != null) {
            if (obj.layer != query.filter.activeLayerId) return false
        }

        val kind = ObjectKindFilter.fromPayload(obj.payload)
        if (kind !in query.filter.frozenAllowedKinds) return false

        return true
    }
}
