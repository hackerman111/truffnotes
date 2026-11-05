package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.model.SceneObject
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.Precondition
import inkspace.core.mutation.RemoveObject
import inkspace.core.mutation.UpdatePayload
import inkspace.core.objects.EraserLayerPolicy
import inkspace.core.objects.EraserMode
import inkspace.core.objects.EraserShape
import inkspace.core.objects.EraserSpec
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.ObjectKindFilter
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Implements `PRECISION`, `SEGMENT`, `STROKE`, and `OBJECT` eraser modes:
 * - Applies layer policy (`ACTIVE_LAYER_ONLY` vs `VISIBLE_UNLOCKED_LAYERS`) and object kind filters
 *   before geometric clipping.
 * - Protects non-stroke objects (`Image`, `Text`, `Shape`, etc.) in `PRECISION`/`SEGMENT`/`STROKE` modes.
 * - Linearly interpolates `(relX, relY, pressure, tilt, timeDeltaMs)` at segment clip boundaries in
 *   `SEGMENT` and `PRECISION` modes and preserves remaining vector sub-strokes.
 */
object ErasePlanner {

    private data class WorldStrokePoint(
        val worldPt: DocPoint,
        val pressure: Float,
        val tilt: Float,
        val timeDeltaMs: Int
    )

    fun planErase(
        read: DocumentRead,
        action: ObjectAction.Erase,
        ctx: PlanContext
    ): Outcome<EditPlan, FeatureError> {
        val path = action.frozenPathPoints
        if (path.isEmpty()) {
            return Outcome.Failure(FeatureError.InvalidParameters("Eraser pathPoints cannot be empty"))
        }
        for (p in path) {
            if (!p.x.isFinite() || !p.y.isFinite()) {
                return Outcome.Failure(FeatureError.InvalidParameters("Eraser path contains non-finite coordinates"))
            }
        }

        val spec = action.spec
        val radius = spec.radiusPt
        val minX = path.minOf { it.x } - radius
        val minY = path.minOf { it.y } - radius
        val maxX = path.maxOf { it.x } + radius
        val maxY = path.maxOf { it.y } + radius
        val searchRect = DocRect(minX, minY, maxX, maxY)

        // Stage 1: Spatial query
        val candidateIds = read.queryBounds(searchRect)

        val notResident = ArrayList<String>()
        for (id in candidateIds) {
            if (read.objectState(id) == ResidencyState.NotResident) {
                notResident.add(id.value)
            }
        }
        if (notResident.isNotEmpty()) {
            return Outcome.Failure(FeatureError.ObjectsNotResident(notResident))
        }

        val preconditions = ArrayList<Precondition>()
        val mutations = ArrayList<Mutation>()
        val parentGroupReplacements = LinkedHashMap<ObjectId, MutableMap<ObjectId, List<ObjectId>>>()
        var splitCounter = 0

        for (id in candidateIds) {
            val obj = read.getObject(id) ?: continue
            if (obj.payload is GroupPayload) continue // Group containers are updated via member replacements
            if (!passesPreClipFilter(read, obj, spec, ctx)) continue
            if (!eraserPathIntersectsExpandedBounds(obj.bounds, path, spec.radiusPt)) continue

            when (spec.mode) {
                EraserMode.OBJECT -> {
                    if (objectIntersectsEraserPath(obj, path, spec, read, ctx)) {
                        preconditions.add(Precondition(obj.id.value, obj.version))
                        mutations.add(RemoveObject(obj.id))
                        recordParentGroupReplacement(read, obj.id, emptyList(), parentGroupReplacements)
                    }
                }

                EraserMode.STROKE -> {
                    if (obj.payload !is StrokePayload) continue
                    if (objectIntersectsEraserPath(obj, path, spec, read, ctx)) {
                        preconditions.add(Precondition(obj.id.value, obj.version))
                        mutations.add(RemoveObject(obj.id))
                        recordParentGroupReplacement(read, obj.id, emptyList(), parentGroupReplacements)
                    }
                }

                EraserMode.SEGMENT, EraserMode.PRECISION -> {
                    val stroke = obj.payload as? StrokePayload ?: continue
                    val clipResult = clipStrokeAgainstEraser(obj, stroke, path, spec)
                    if (!clipResult.wasClipped) continue

                    preconditions.add(Precondition(obj.id.value, obj.version))
                    mutations.add(RemoveObject(obj.id))

                    val replacementIds = ArrayList<ObjectId>(clipResult.remainingSubStrokes.size)
                    for (subStroke in clipResult.remainingSubStrokes) {
                        splitCounter += 1
                        val subId = ObjectId("${action.splitIdPrefix}-${obj.id.value}-$splitCounter")
                        replacementIds.add(subId)
                        val subBounds = computeSubStrokeWorldBounds(subStroke, obj.transform)
                        mutations.add(
                            InsertObject(
                                space = obj.space,
                                layer = obj.layer,
                                id = subId,
                                payload = subStroke,
                                bounds = subBounds,
                                transform = obj.transform,
                                style = obj.style,
                                isLocked = false,
                                targetVersion = 1L
                            )
                        )
                    }
                    recordParentGroupReplacement(read, obj.id, replacementIds, parentGroupReplacements)
                }
            }
        }

        // Update any parent groups whose members were erased or split
        for ((groupId, memberMap) in parentGroupReplacements) {
            val groupObj = read.getObject(groupId) ?: continue
            val groupPayload = groupObj.payload as? GroupPayload ?: continue
            preconditions.add(Precondition(groupObj.id.value, groupObj.version))

            val nextMembers = ArrayList<ObjectId>()
            for (m in groupPayload.members) {
                val repl = memberMap[m]
                if (repl != null) {
                    nextMembers.addAll(repl)
                } else {
                    nextMembers.add(m)
                }
            }
            if (nextMembers.isEmpty()) {
                mutations.add(RemoveObject(groupId))
            } else {
                mutations.add(
                    UpdatePayload(
                        id = groupId,
                        newPayload = GroupPayload(nextMembers),
                        newBounds = groupObj.bounds
                    )
                )
            }
        }

        if (mutations.isEmpty()) {
            return Outcome.Failure(FeatureError.NoOpAction("Eraser path did not intersect any eligible objects"))
        }

        return Outcome.Success(
            EditPlan(
                base = read.version,
                preconditions = preconditions,
                mutations = mutations,
                gesture = ctx.gestureId,
                label = UndoLabel("Erase")
            )
        )
    }

    private fun passesPreClipFilter(
        read: DocumentRead,
        obj: SceneObject,
        spec: EraserSpec,
        ctx: PlanContext
    ): Boolean {
        if (obj.space != ctx.activeSpaceId) return false
        if (obj.isLocked || read.isLocked(obj.id)) return false
        if (!obj.style.isVisible) return false

        val layer = read.getLayer(obj.layer)
        if (layer != null) {
            if (!layer.isVisible || layer.isLocked) return false
        } else if (read.isLayerLocked(obj.layer)) {
            return false
        }

        when (spec.layerPolicy) {
            EraserLayerPolicy.ACTIVE_LAYER_ONLY -> {
                if (obj.layer != ctx.activeLayerId) return false
            }
            EraserLayerPolicy.VISIBLE_UNLOCKED_LAYERS -> {
                // Already checked visibility and lock above
            }
        }

        val kind = ObjectKindFilter.fromPayload(obj.payload)
        if (kind !in spec.frozenAllowedKinds) return false

        // Standard stroke modes never erase non-stroke objects (images, text, shapes)
        if (spec.mode != EraserMode.OBJECT && obj.payload !is StrokePayload) {
            return false
        }

        return true
    }

    private fun eraserPathIntersectsExpandedBounds(
        bounds: DocRect,
        eraserPath: List<DocPoint>,
        radiusPt: Double
    ): Boolean {
        val exp = DocRect(
            left = bounds.left - radiusPt,
            top = bounds.top - radiusPt,
            right = bounds.right + radiusPt,
            bottom = bounds.bottom + radiusPt
        )
        if (eraserPath.size == 1) {
            return exp.contains(eraserPath[0])
        }
        for (i in 0 until eraserPath.size - 1) {
            if (HitTest.segmentIntersectsRect(eraserPath[i], eraserPath[i + 1], exp)) {
                return true
            }
        }
        return false
    }

    private fun recordParentGroupReplacement(
        read: DocumentRead,
        memberId: ObjectId,
        replacements: List<ObjectId>,
        out: MutableMap<ObjectId, MutableMap<ObjectId, List<ObjectId>>>
    ) {
        val parentId = read.parentGroupOf(memberId) ?: return
        val map = out.getOrPut(parentId) { LinkedHashMap() }
        map[memberId] = replacements
    }

    private fun objectIntersectsEraserPath(
        obj: SceneObject,
        eraserPath: List<DocPoint>,
        spec: EraserSpec,
        read: DocumentRead,
        ctx: PlanContext
    ): Boolean {
        val pts = HitTest.extractWorldPoints(obj, read, ctx.geometryPort)
        for (p in pts) {
            if (isPointInsideEraserFootprint(p, eraserPath, spec)) return true
        }
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val minT = findClosestParameterOnSegmentToEraserPath(a, b, eraserPath)
            val mid = interpolateDocPoint(a, b, minT)
            if (isPointInsideEraserFootprint(mid, eraserPath, spec)) return true
        }
        if (obj.payload !is StrokePayload) {
            for (ep in eraserPath) {
                val expanded = DocRect(
                    obj.bounds.left - spec.radiusPt,
                    obj.bounds.top - spec.radiusPt,
                    obj.bounds.right + spec.radiusPt,
                    obj.bounds.bottom + spec.radiusPt
                )
                if (expanded.contains(ep)) return true
            }
        }
        return false
    }

    private data class StrokeClipResult(
        val wasClipped: Boolean,
        val remainingSubStrokes: List<StrokePayload>
    )

    private fun clipStrokeAgainstEraser(
        obj: SceneObject,
        stroke: StrokePayload,
        eraserPath: List<DocPoint>,
        spec: EraserSpec
    ): StrokeClipResult {
        val samples = stroke.samples
        if (samples.isEmpty()) return StrokeClipResult(false, emptyList())

        val worldPoints = samples.map { s ->
            val localPt = DocPoint(stroke.origin.x + s.relX, stroke.origin.y + s.relY)
            WorldStrokePoint(
                worldPt = obj.transform.transformPoint(localPt),
                pressure = s.pressure,
                tilt = s.tilt,
                timeDeltaMs = s.timeDeltaMs
            )
        }

        if (worldPoints.size == 1) {
            val hit = isPointInsideEraserFootprint(worldPoints[0].worldPt, eraserPath, spec)
            return if (hit) {
                StrokeClipResult(true, emptyList())
            } else {
                StrokeClipResult(false, listOf(stroke))
            }
        }

        var anyClipped = false
        val chains = ArrayList<MutableList<WorldStrokePoint>>()
        var currentChain: MutableList<WorldStrokePoint>? = null

        fun appendToChain(pt: WorldStrokePoint) {
            val chain = currentChain
            if (chain == null) {
                val next = ArrayList<WorldStrokePoint>()
                next.add(pt.copy(timeDeltaMs = 0))
                currentChain = next
                chains.add(next)
            } else {
                val prev = chain.last()
                val dx = pt.worldPt.x - prev.worldPt.x
                val dy = pt.worldPt.y - prev.worldPt.y
                if (dx * dx + dy * dy > 1e-8) {
                    chain.add(pt)
                }
            }
        }

        fun closeChain() {
            currentChain = null
        }

        for (i in 0 until worldPoints.size - 1) {
            val p0 = worldPoints[i]
            val p1 = worldPoints[i + 1]
            val in0 = isPointInsideEraserFootprint(p0.worldPt, eraserPath, spec)
            val in1 = isPointInsideEraserFootprint(p1.worldPt, eraserPath, spec)
            val tClosest = findClosestParameterOnSegmentToEraserPath(p0.worldPt, p1.worldPt, eraserPath)
            val inMid = isPointInsideEraserFootprint(
                interpolateDocPoint(p0.worldPt, p1.worldPt, tClosest),
                eraserPath,
                spec
            )

            when {
                !in0 && !in1 && !inMid -> {
                    // Entire segment outside eraser
                    appendToChain(p0)
                    if (i == worldPoints.size - 2) {
                        appendToChain(p1)
                    }
                }

                in0 && in1 -> {
                    // Entire segment inside eraser
                    anyClipped = true
                    closeChain()
                }

                !in0 && in1 -> {
                    // Enters eraser at tEnter in [0, 1]
                    anyClipped = true
                    val tEnter = bisectEraserBoundary(p0.worldPt, p1.worldPt, 0.0, 1.0, false, eraserPath, spec)
                    appendToChain(p0)
                    appendToChain(interpolateStrokePoint(p0, p1, tEnter))
                    closeChain()
                }

                in0 && !in1 -> {
                    // Exits eraser at tExit in [0, 1]
                    anyClipped = true
                    val tExit = bisectEraserBoundary(p0.worldPt, p1.worldPt, 0.0, 1.0, true, eraserPath, spec)
                    closeChain()
                    val exitPt = interpolateStrokePoint(p0, p1, tExit)
                    appendToChain(exitPt)
                    if (i == worldPoints.size - 2) {
                        val remDt = ((1.0 - tExit) * p1.timeDeltaMs).roundToInt().coerceAtLeast(1)
                        appendToChain(p1.copy(timeDeltaMs = remDt))
                    }
                }

                !in0 && !in1 && inMid -> {
                    // Segment enters at tEnter in [0, tClosest] and exits at tExit in [tClosest, 1]
                    anyClipped = true
                    val tEnter = bisectEraserBoundary(p0.worldPt, p1.worldPt, 0.0, tClosest, false, eraserPath, spec)
                    val tExit = bisectEraserBoundary(p0.worldPt, p1.worldPt, tClosest, 1.0, true, eraserPath, spec)
                    appendToChain(p0)
                    appendToChain(interpolateStrokePoint(p0, p1, tEnter))
                    closeChain()
                    appendToChain(interpolateStrokePoint(p0, p1, tExit))
                    if (i == worldPoints.size - 2) {
                        val remDt = ((1.0 - tExit) * p1.timeDeltaMs).roundToInt().coerceAtLeast(1)
                        appendToChain(p1.copy(timeDeltaMs = remDt))
                    }
                }
            }
        }

        if (!anyClipped) {
            return StrokeClipResult(false, listOf(stroke))
        }

        val invTransform = obj.transform.invert() ?: inkspace.core.model.AffineTransform2D.IDENTITY
        val subStrokes = ArrayList<StrokePayload>()

        for (chain in chains) {
            if (chain.size < 2) continue
            val localPoints = chain.map { wp -> invTransform.transformPoint(wp.worldPt) }
            val newOrigin = localPoints.first()
            val newSamples = ArrayList<StrokeSamplePoint>(chain.size)
            for (k in chain.indices) {
                val lp = localPoints[k]
                val wp = chain[k]
                newSamples.add(
                    StrokeSamplePoint(
                        relX = (lp.x - newOrigin.x).toFloat(),
                        relY = (lp.y - newOrigin.y).toFloat(),
                        pressure = wp.pressure.coerceIn(0.0f, 1.0f),
                        tilt = wp.tilt.coerceIn(0.0f, 1.0f),
                        timeDeltaMs = if (k == 0) 0 else wp.timeDeltaMs.coerceAtLeast(0)
                    )
                )
            }
            subStrokes.add(
                stroke.copy(
                    origin = newOrigin,
                    samples = newSamples
                )
            )
        }

        return StrokeClipResult(true, subStrokes)
    }

    private fun bisectEraserBoundary(
        a: DocPoint,
        b: DocPoint,
        tLow: Double,
        tHigh: Double,
        lowIsInside: Boolean,
        eraserPath: List<DocPoint>,
        spec: EraserSpec
    ): Double {
        var lo = tLow
        var hi = tHigh
        repeat(24) {
            val mid = (lo + hi) * 0.5
            val pt = interpolateDocPoint(a, b, mid)
            val inside = isPointInsideEraserFootprint(pt, eraserPath, spec)
            if (inside == lowIsInside) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return (lo + hi) * 0.5
    }

    private fun interpolateDocPoint(a: DocPoint, b: DocPoint, t: Double): DocPoint {
        return DocPoint(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t
        )
    }

    private fun interpolateStrokePoint(
        a: WorldStrokePoint,
        b: WorldStrokePoint,
        t: Double
    ): WorldStrokePoint {
        val tf = t.toFloat().coerceIn(0.0f, 1.0f)
        return WorldStrokePoint(
            worldPt = interpolateDocPoint(a.worldPt, b.worldPt, t),
            pressure = (a.pressure + (b.pressure - a.pressure) * tf).coerceIn(0.0f, 1.0f),
            tilt = (a.tilt + (b.tilt - a.tilt) * tf).coerceIn(0.0f, 1.0f),
            timeDeltaMs = (b.timeDeltaMs * t).roundToInt().coerceAtLeast(0)
        )
    }

    private fun isPointInsideEraserFootprint(
        pt: DocPoint,
        eraserPath: List<DocPoint>,
        spec: EraserSpec
    ): Boolean {
        val r = spec.radiusPt
        val rSq = r * r
        if (eraserPath.size == 1) {
            return pointInsideSingleEraserStamp(pt, eraserPath[0], r, rSq, spec.shape)
        }
        for (i in 0 until eraserPath.size - 1) {
            val e0 = eraserPath[i]
            val e1 = eraserPath[i + 1]
            when (spec.shape) {
                EraserShape.CIRCLE -> {
                    if (HitTest.distancePointToSegmentSq(pt, e0, e1) <= rSq + 1e-9) {
                        return true
                    }
                }
                EraserShape.SQUARE -> {
                    val dx = e1.x - e0.x
                    val dy = e1.y - e0.y
                    val lenSq = dx * dx + dy * dy
                    val t = if (lenSq <= 1e-12) {
                        0.0
                    } else {
                        (((pt.x - e0.x) * dx + (pt.y - e0.y) * dy) / lenSq).coerceIn(0.0, 1.0)
                    }
                    val proj = DocPoint(e0.x + t * dx, e0.y + t * dy)
                    if (abs(pt.x - proj.x) <= r + 1e-9 && abs(pt.y - proj.y) <= r + 1e-9) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun pointInsideSingleEraserStamp(
        pt: DocPoint,
        center: DocPoint,
        r: Double,
        rSq: Double,
        shape: EraserShape
    ): Boolean {
        val dx = abs(pt.x - center.x)
        val dy = abs(pt.y - center.y)
        return when (shape) {
            EraserShape.CIRCLE -> (dx * dx + dy * dy) <= rSq + 1e-9
            EraserShape.SQUARE -> dx <= r + 1e-9 && dy <= r + 1e-9
        }
    }

    private fun findClosestParameterOnSegmentToEraserPath(
        a: DocPoint,
        b: DocPoint,
        eraserPath: List<DocPoint>
    ): Double {
        var bestT = 0.5
        var bestDistSq = Double.POSITIVE_INFINITY

        fun checkCandidate(t: Double) {
            val pt = interpolateDocPoint(a, b, t)
            val dSq = minDistSqToEraserPath(pt, eraserPath)
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestT = t
            }
        }

        checkCandidate(0.0)
        checkCandidate(0.5)
        checkCandidate(1.0)

        val abX = b.x - a.x
        val abY = b.y - a.y
        val abLenSq = abX * abX + abY * abY

        if (abLenSq > 1e-12) {
            for (ep in eraserPath) {
                val t = (((ep.x - a.x) * abX + (ep.y - a.y) * abY) / abLenSq).coerceIn(0.0, 1.0)
                checkCandidate(t)
            }
        }
        for (i in 0 until eraserPath.size - 1) {
            val e0 = eraserPath[i]
            val e1 = eraserPath[i + 1]
            checkCandidate(closestTOnSegmentAToSegmentB(a, b, e0, e1))
        }
        return bestT
    }

    private fun closestTOnSegmentAToSegmentB(
        a0: DocPoint,
        a1: DocPoint,
        b0: DocPoint,
        b1: DocPoint
    ): Double {
        val ux = a1.x - a0.x
        val uy = a1.y - a0.y
        val vx = b1.x - b0.x
        val vy = b1.y - b0.y
        val wx = a0.x - b0.x
        val wy = a0.y - b0.y

        val a = ux * ux + uy * uy
        val b = ux * vx + uy * vy
        val c = vx * vx + vy * vy
        val d = ux * wx + uy * wy
        val e = vx * wx + vy * wy
        val denom = a * c - b * b
        if (denom <= 1e-12) return 0.5
        return ((b * e - c * d) / denom).coerceIn(0.0, 1.0)
    }

    private fun minDistSqToEraserPath(pt: DocPoint, eraserPath: List<DocPoint>): Double {
        if (eraserPath.size == 1) {
            val dx = pt.x - eraserPath[0].x
            val dy = pt.y - eraserPath[0].y
            return dx * dx + dy * dy
        }
        var minSq = Double.POSITIVE_INFINITY
        for (i in 0 until eraserPath.size - 1) {
            val d = HitTest.distancePointToSegmentSq(pt, eraserPath[i], eraserPath[i + 1])
            if (d < minSq) minSq = d
        }
        return minSq
    }

    private fun computeSubStrokeWorldBounds(
        stroke: StrokePayload,
        transform: inkspace.core.model.AffineTransform2D
    ): DocRect {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (s in stroke.samples) {
            val lp = DocPoint(stroke.origin.x + s.relX, stroke.origin.y + s.relY)
            val wp = transform.transformPoint(lp)
            if (wp.x < minX) minX = wp.x
            if (wp.y < minY) minY = wp.y
            if (wp.x > maxX) maxX = wp.x
            if (wp.y > maxY) maxY = wp.y
        }
        val scale = sqrt(
            max(
                transform.m00 * transform.m00 + transform.m10 * transform.m10,
                transform.m01 * transform.m01 + transform.m11 * transform.m11
            )
        ).coerceAtLeast(1.0)
        val pad = stroke.baseWidthPt * scale * 0.5
        return DocRect(
            min(minX - pad, maxX + pad),
            min(minY - pad, maxY + pad),
            max(minX - pad, maxX + pad),
            max(minY - pad, maxY + pad)
        )
    }
}
