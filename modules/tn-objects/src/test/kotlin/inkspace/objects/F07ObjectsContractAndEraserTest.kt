package inkspace.objects

import inkspace.core.error.FeatureError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.limits.Limits
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentKind
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.ObjectStyle
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.History
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.SetLayerLock
import inkspace.core.mutation.SetLayerStyle
import inkspace.core.mutation.SetObjectLock
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.objects.EraserLayerPolicy
import inkspace.core.objects.EraserMode
import inkspace.core.objects.EraserShape
import inkspace.core.objects.EraserSpec
import inkspace.core.objects.FlipAxis
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.ObjectKindFilter
import inkspace.core.objects.PlanContext
import inkspace.core.objects.SelectionCombinator
import inkspace.core.objects.SelectionFilter
import inkspace.core.objects.SelectionMode
import inkspace.core.objects.SelectionQuery
import inkspace.core.objects.SelectionRule
import inkspace.core.objects.SelectionSet
import inkspace.core.objects.StylePatch
import inkspace.core.objects.TransferId
import inkspace.core.objects.ZOrderCommand
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.payload.TextPayload
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class F07ObjectsContractAndEraserTest {

    private val docId = DocumentId("doc-f07-test")
    private val spaceId = SpaceId("space-1")
    private val pageId = PageId("page-1")
    private val layer1 = LayerId("layer-1")
    private val layer2 = LayerId("layer-2")

    private fun createBaseState(): DocumentState {
        return DocumentState.createInitial(
            documentId = docId,
            kind = DocumentKind.Notebook,
            initialSpace = spaceId,
            initialPage = PageDescriptor(pageId, spaceId, 600.0, 800.0),
            initialLayer = LayerDescriptor(layer1, pageId, "Layer 1")
        )
    }

    private fun executePlan(state: DocumentState, plan: EditPlan, history: History? = null) {
        val validated = validatePlan(state, plan, Limits())
        assertTrue(validated.isSuccess, "Expected valid plan, got: ${validated.errorOrNull()}")
        val applied = applyPlan(state, validated.getOrNull()!!, Limits())
        assertTrue(applied.isSuccess, "Expected apply success, got: ${applied.errorOrNull()}")
        history?.record(plan, applied.getOrNull()!!)
    }

    private fun makeStroke(
        id: String,
        points: List<DocPoint>,
        layer: LayerId = layer1,
        color: Int = 0xFF112233.toInt(),
        widthPt: Double = 2.0,
        zOrder: Long = 0L,
        isLocked: Boolean = false
    ): SceneObject {
        val origin = points.first()
        val samples = points.mapIndexed { idx, p ->
            StrokeSamplePoint(
                relX = (p.x - origin.x).toFloat(),
                relY = (p.y - origin.y).toFloat(),
                pressure = (0.2f + 0.2f * idx).coerceIn(0.0f, 1.0f),
                tilt = (0.1f + 0.1f * idx).coerceIn(0.0f, 1.0f),
                timeDeltaMs = if (idx == 0) 0 else 10
            )
        }
        val minX = points.minOf { it.x } - widthPt
        val minY = points.minOf { it.y } - widthPt
        val maxX = points.maxOf { it.x } + widthPt
        val maxY = points.maxOf { it.y } + widthPt
        return SceneObject(
            id = ObjectId(id),
            space = spaceId,
            layer = layer,
            bounds = DocRect(minX, minY, maxX, maxY),
            payload = StrokePayload(
                brushId = "ball",
                color = color,
                baseWidthPt = widthPt,
                origin = origin,
                samples = samples
            ),
            style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = zOrder),
            isLocked = isLocked
        )
    }

    @Test
    fun lasso_contains_intersects() {
        val state = createBaseState()
        // s1: fully inside [20..80, 20..80]
        val s1 = makeStroke("s1", listOf(DocPoint(30.0, 30.0), DocPoint(70.0, 70.0)))
        // s2: crosses boundary from (50, 50) to (150, 50)
        val s2 = makeStroke("s2", listOf(DocPoint(50.0, 50.0), DocPoint(150.0, 50.0)))
        // s3: diagonal from (10, 90) to (90, 10) whose AABB [8..92, 8..92] overlaps a small box [70..90, 70..90]
        //     at bottom-right corner, even though the segment x+y=100 never touches [70..90, 70..90] (since x+y >= 140)!
        val s3 = makeStroke("s3", listOf(DocPoint(10.0, 90.0), DocPoint(90.0, 10.0)), widthPt = 1.0)
        // Two separate Textboxes t1 and t2 inside [20..80, 20..80]
        val t1 = SceneObject(
            id = ObjectId("t1"),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(25.0, 25.0, 45.0, 40.0),
            payload = TextPayload(text = "First Box", fontSizePt = 12.0)
        )
        val t2 = SceneObject(
            id = ObjectId("t2"),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(50.0, 25.0, 75.0, 40.0),
            payload = TextPayload(text = "Second Box", fontSizePt = 12.0)
        )

        for (obj in listOf(s1, s2, s3, t1, t2)) {
            state.putResidentObjectDirect(obj)
        }

        // 1. Rectangle CONTAINS on [20..80, 20..80] -> selects s1, t1, t2; excludes s2 (crosses out) and s3
        val containsRes = SelectionEngine.select(
            read = state,
            current = SelectionSet(),
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.RECTANGLE,
                rule = SelectionRule.CONTAINS,
                rect = DocRect(20.0, 20.0, 80.0, 80.0)
            )
        )
        assertTrue(containsRes.isSuccess)
        assertEquals(
            setOf(ObjectId("s1"), ObjectId("t1"), ObjectId("t2")),
            containsRes.getOrNull()!!.selectedIds
        )

        // 2. Precise hit test: Rectangle INTERSECTS on [70..88, 70..88]:
        //    s1's endpoint (70, 70) touches it, whereas s3's AABB [9..91, 9..91] overlaps [70..88, 70..88]
        //    but s3's actual line segment x+y=100 is far away from [70..88, 70..88]!
        val preciseRes = SelectionEngine.select(
            read = state,
            current = SelectionSet(),
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.RECTANGLE,
                rule = SelectionRule.INTERSECTS,
                rect = DocRect(72.0, 72.0, 88.0, 88.0)
            )
        )
        assertTrue(preciseRes.isSuccess)
        assertFalse(
            preciseRes.getOrNull()!!.selectedIds.contains(ObjectId("s3")),
            "s3 AABB overlaps query rect, but precise segment hit test must reject it"
        )

        // 3. Freehand lasso INTERSECTS around [20..80, 20..80] -> includes s2 (crosses lasso edge)
        val freehandPolygon = listOf(
            DocPoint(20.0, 20.0),
            DocPoint(80.0, 20.0),
            DocPoint(80.0, 80.0),
            DocPoint(20.0, 80.0)
        )
        val freehandIntersects = SelectionEngine.select(
            read = state,
            current = SelectionSet(),
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.FREEHAND,
                rule = SelectionRule.INTERSECTS,
                polygonPoints = freehandPolygon
            )
        )
        assertTrue(freehandIntersects.isSuccess)
        val selected = freehandIntersects.getOrNull()!!.selectedIds
        assertTrue(selected.contains(ObjectId("s1")))
        assertTrue(selected.contains(ObjectId("s2")))
        assertTrue(selected.contains(ObjectId("t1")))
        assertTrue(selected.contains(ObjectId("t2")))

        // 4. Mixed selection move & style must NOT merge t1 and t2 into a single Textbox
        val movePlan = ObjectPlanner.plan(
            read = state,
            action = ObjectAction.Move(targets = selected, deltaX = 10.0, deltaY = 5.0),
            ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)
        )
        assertTrue(movePlan.isSuccess)
        executePlan(state, movePlan.getOrNull()!!)

        val t1After = state.getObject(ObjectId("t1"))
        val t2After = state.getObject(ObjectId("t2"))
        assertNotNull(t1After)
        assertNotNull(t2After)
        assertEquals("First Box", (t1After.payload as TextPayload).text)
        assertEquals("Second Box", (t2After.payload as TextPayload).text)
    }

    @Test
    fun add_subtract_selection() {
        val state = createBaseState()
        val s1 = makeStroke("s1", listOf(DocPoint(20.0, 20.0), DocPoint(40.0, 40.0)))
        val s2 = makeStroke("s2", listOf(DocPoint(60.0, 20.0), DocPoint(80.0, 40.0)))
        val s3 = makeStroke("s3", listOf(DocPoint(120.0, 20.0), DocPoint(140.0, 40.0)))
        state.putResidentObjectDirect(s1)
        state.putResidentObjectDirect(s2)
        state.putResidentObjectDirect(s3)

        // REPLACE selects {s1, s2}
        val sel1 = SelectionEngine.select(
            read = state,
            current = SelectionSet(),
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.RECTANGLE,
                rule = SelectionRule.INTERSECTS,
                combinator = SelectionCombinator.REPLACE,
                rect = DocRect(10.0, 10.0, 90.0, 50.0)
            )
        ).getOrNull()!!
        assertEquals(setOf(ObjectId("s1"), ObjectId("s2")), sel1.selectedIds)

        // ADD selects {s3} -> {s1, s2, s3}
        val sel2 = SelectionEngine.select(
            read = state,
            current = sel1,
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.RECTANGLE,
                rule = SelectionRule.INTERSECTS,
                combinator = SelectionCombinator.ADD,
                rect = DocRect(110.0, 10.0, 150.0, 50.0)
            )
        ).getOrNull()!!
        assertEquals(setOf(ObjectId("s1"), ObjectId("s2"), ObjectId("s3")), sel2.selectedIds)

        // SUBTRACT removes {s2} -> {s1, s3}
        val sel3 = SelectionEngine.select(
            read = state,
            current = sel2,
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.RECTANGLE,
                rule = SelectionRule.INTERSECTS,
                combinator = SelectionCombinator.SUBTRACT,
                rect = DocRect(55.0, 10.0, 90.0, 50.0)
            )
        ).getOrNull()!!
        assertEquals(setOf(ObjectId("s1"), ObjectId("s3")), sel3.selectedIds)
    }

    @Test
    fun hidden_locked() {
        val state = createBaseState()
        val sUnlocked = makeStroke("s-unlocked", listOf(DocPoint(20.0, 20.0), DocPoint(40.0, 40.0)))
        val sLockedObj = makeStroke("s-locked-obj", listOf(DocPoint(25.0, 25.0), DocPoint(45.0, 45.0)), isLocked = true)
        state.putResidentObjectDirect(sUnlocked)
        state.putResidentObjectDirect(sLockedObj)

        // Add layer2 and a stroke on layer2
        executePlan(
            state,
            EditPlan(
                base = state.version,
                mutations = listOf(
                    inkspace.core.mutation.AddLayer(LayerDescriptor(layer2, pageId, "Layer 2"))
                ),
                gesture = GestureId("g-add-l2"),
                label = inkspace.core.id.UndoLabel("Add Layer 2")
            )
        )
        val sLayer2 = makeStroke("s-layer2", listOf(DocPoint(30.0, 30.0), DocPoint(50.0, 50.0)), layer = layer2)
        state.putResidentObjectDirect(sLayer2)

        // Hide layer2
        executePlan(
            state,
            EditPlan(
                base = state.version,
                mutations = listOf(SetLayerStyle(layer2, opacity = 1.0f, isVisible = false)),
                gesture = GestureId("g-hide-l2"),
                label = inkspace.core.id.UndoLabel("Hide Layer 2")
            )
        )

        // Selection over [0..100, 0..100] must only return s-unlocked (s-locked-obj is locked, s-layer2 is on hidden layer)
        val sel = SelectionEngine.select(
            read = state,
            current = SelectionSet(),
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.RECTANGLE,
                rule = SelectionRule.INTERSECTS,
                rect = DocRect(0.0, 0.0, 100.0, 100.0)
            )
        ).getOrNull()!!
        assertEquals(setOf(ObjectId("s-unlocked")), sel.selectedIds)

        // Attempting to move {s-unlocked, s-locked-obj} must be rejected atomically with LockedTarget
        val moveLocked = ObjectPlanner.plan(
            read = state,
            action = ObjectAction.Move(
                targets = setOf(ObjectId("s-unlocked"), ObjectId("s-locked-obj")),
                deltaX = 20.0,
                deltaY = 20.0
            ),
            ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)
        )
        assertTrue(moveLocked.isFailure)
        assertTrue(moveLocked.errorOrNull() is FeatureError.LockedTarget)

        // Lock layer1 and attempt any edit on s-unlocked -> rejected with LockedTarget
        executePlan(
            state,
            EditPlan(
                base = state.version,
                mutations = listOf(SetLayerLock(layer1, isLocked = true)),
                gesture = GestureId("g-lock-l1"),
                label = inkspace.core.id.UndoLabel("Lock Layer 1")
            )
        )
        val styleOnLockedLayer = ObjectPlanner.plan(
            read = state,
            action = ObjectAction.ApplyStyle(
                targets = setOf(ObjectId("s-unlocked")),
                patch = StylePatch(colorArgb = 0xFFFF0000.toInt())
            ),
            ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)
        )
        assertTrue(styleOnLockedLayer.isFailure)
        assertTrue(styleOnLockedLayer.errorOrNull() is FeatureError.LockedTarget)

        // Unlock layer1, register a NotResident object in [0..100, 0..100], verify ObjectsNotResident is reported
        executePlan(
            state,
            EditPlan(
                base = state.version,
                mutations = listOf(SetLayerLock(layer1, isLocked = false)),
                gesture = GestureId("g-unlock-l1"),
                label = inkspace.core.id.UndoLabel("Unlock Layer 1")
            )
        )
        state.registerNonResidentObject(
            id = ObjectId("s-cold"),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(20.0, 20.0, 40.0, 40.0),
            isLocked = false,
            version = 1L,
            estimatedBytes = 512
        )
        val coldSel = SelectionEngine.select(
            read = state,
            current = SelectionSet(),
            query = SelectionQuery(
                spaceId = spaceId,
                mode = SelectionMode.RECTANGLE,
                rule = SelectionRule.INTERSECTS,
                rect = DocRect(0.0, 0.0, 100.0, 100.0)
            )
        )
        assertTrue(coldSel.isFailure)
        assertTrue(coldSel.errorOrNull() is FeatureError.ObjectsNotResident)
    }

    @Test
    fun transform_inverse() {
        val state = createBaseState()
        val history = History()
        val s1 = makeStroke("s1", listOf(DocPoint(100.0, 100.0), DocPoint(200.0, 150.0)))
        state.putResidentObjectDirect(s1)
        val origObj = state.getObject(ObjectId("s1"))!!
        val ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)
        val targets = setOf(ObjectId("s1"))

        // 1. Live preview does NOT mutate DocumentState
        val preview = TransformPlanner.buildPreview(
            read = state,
            targets = targets,
            deltaTransform = AffineTransform2D.translation(25.0, -15.0)
        )
        assertTrue(preview.isSuccess)
        assertEquals(origObj.bounds, state.getObject(ObjectId("s1"))!!.bounds, "Preview must not mutate state")
        assertEquals(origObj.bounds.translate(25.0, -15.0), preview.getOrNull()!!.previewBounds)

        // 2. Move, Scale, Rotate, Flip Horizontal, Flip Vertical -> then inverse/undo
        val pivot = DocPoint(150.0, 125.0)
        val actions = listOf(
            ObjectAction.Move(targets, deltaX = 30.0, deltaY = -20.0),
            ObjectAction.Scale(targets, scaleX = 1.5, scaleY = 0.8, pivot = pivot),
            ObjectAction.Rotate(targets, angleRadians = PI / 6.0, pivot = pivot),
            ObjectAction.Flip(targets, axis = FlipAxis.HORIZONTAL, pivot = pivot),
            ObjectAction.Flip(targets, axis = FlipAxis.VERTICAL, pivot = pivot)
        )

        for ((idx, act) in actions.withIndex()) {
            val planOut = ObjectPlanner.plan(state, act, ctx.copy(gestureId = GestureId("g-tx-$idx")))
            assertTrue(planOut.isSuccess, "Failed action $act: ${planOut.errorOrNull()}")
            executePlan(state, planOut.getOrNull()!!, history)
        }

        // Also verify algebraic matrix inverse roundtrip error <= 0.01 pt
        val currentTransform = state.getObject(ObjectId("s1"))!!.transform
        val inv = currentTransform.invert()
        assertNotNull(inv)
        val roundTripPt = inv.transformPoint(currentTransform.transformPoint(DocPoint(123.45, 678.9)))
        assertTrue(abs(roundTripPt.x - 123.45) <= 0.01)
        assertTrue(abs(roundTripPt.y - 678.9) <= 0.01)

        // Undo all 5 operations -> exact initial object state restored
        repeat(actions.size) {
            val undoPlan = history.undo(state.version)
            assertNotNull(undoPlan)
            executePlan(state, undoPlan)
        }

        val restored = state.getObject(ObjectId("s1"))!!
        assertTrue(abs(restored.bounds.left - origObj.bounds.left) <= 0.01)
        assertTrue(abs(restored.bounds.top - origObj.bounds.top) <= 0.01)
        assertTrue(abs(restored.bounds.right - origObj.bounds.right) <= 0.01)
        assertTrue(abs(restored.bounds.bottom - origObj.bounds.bottom) <= 0.01)
        assertEquals(origObj.transform, restored.transform)
    }

    @Test
    fun nested_group_once() {
        val state = createBaseState()
        val history = History()
        val s1 = makeStroke("s1", listOf(DocPoint(10.0, 10.0), DocPoint(30.0, 30.0)))
        val s2 = makeStroke("s2", listOf(DocPoint(40.0, 10.0), DocPoint(60.0, 30.0)))
        val s3 = makeStroke("s3", listOf(DocPoint(70.0, 10.0), DocPoint(90.0, 30.0)))
        state.putResidentObjectDirect(s1)
        state.putResidentObjectDirect(s2)
        state.putResidentObjectDirect(s3)

        val ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)

        // Group {s1, s2} -> g1
        val g1Id = ObjectId("g1")
        val planG1 = ObjectPlanner.plan(
            state,
            ObjectAction.Group(targets = setOf(ObjectId("s1"), ObjectId("s2")), newGroupId = g1Id),
            ctx.copy(gestureId = GestureId("g-group-1"))
        )
        assertTrue(planG1.isSuccess)
        executePlan(state, planG1.getOrNull()!!, history)

        // Group {g1, s3} -> g2 (nested group)
        val g2Id = ObjectId("g2")
        val planG2 = ObjectPlanner.plan(
            state,
            ObjectAction.Group(targets = setOf(g1Id, ObjectId("s3")), newGroupId = g2Id),
            ctx.copy(gestureId = GestureId("g-group-2"))
        )
        assertTrue(planG2.isSuccess)
        executePlan(state, planG2.getOrNull()!!, history)

        val s1Before = state.getObject(ObjectId("s1"))!!.bounds
        val s3Before = state.getObject(ObjectId("s3"))!!.bounds

        // Move with redundant targets {g2, g1, s1} -> must apply (+50, +25) ONCE to s1, s2, s3, g1, g2!
        val moveOut = ObjectPlanner.plan(
            state,
            ObjectAction.Move(targets = setOf(g2Id, g1Id, ObjectId("s1")), deltaX = 50.0, deltaY = 25.0),
            ctx.copy(gestureId = GestureId("g-move-nested"))
        )
        assertTrue(moveOut.isSuccess)
        executePlan(state, moveOut.getOrNull()!!, history)

        val s1After = state.getObject(ObjectId("s1"))!!.bounds
        val s3After = state.getObject(ObjectId("s3"))!!.bounds
        assertEquals(s1Before.left + 50.0, s1After.left, 1e-6, "s1 must be moved once, not 3 times")
        assertEquals(s1Before.top + 25.0, s1After.top, 1e-6)
        assertEquals(s3Before.left + 50.0, s3After.left, 1e-6)

        // Edit inside group g2 (enteredGroupId = g2): move s3 by (+10, 0) without ungrouping g2
        val editInsidePlan = ObjectPlanner.plan(
            state,
            ObjectAction.Move(targets = setOf(ObjectId("s3")), deltaX = 10.0, deltaY = 0.0),
            ctx.copy(enteredGroupId = g2Id, gestureId = GestureId("g-edit-inside"))
        )
        assertTrue(editInsidePlan.isSuccess)
        executePlan(state, editInsidePlan.getOrNull()!!, history)

        assertEquals(s3After.left + 10.0, state.getObject(ObjectId("s3"))!!.bounds.left, 1e-6)
        // g2 still exists and its bounds expanded by +10 on the right
        val g2Obj = state.getObject(g2Id)
        assertNotNull(g2Obj)
        assertTrue(g2Obj.payload is GroupPayload)
        assertEquals(state.getObject(ObjectId("s3"))!!.bounds.right, g2Obj.bounds.right, 1e-6)

        // Ungroup g2
        val ungroupPlan = ObjectPlanner.plan(
            state,
            ObjectAction.Ungroup(groupIds = setOf(g2Id)),
            ctx.copy(gestureId = GestureId("g-ungroup-2"))
        )
        assertTrue(ungroupPlan.isSuccess)
        executePlan(state, ungroupPlan.getOrNull()!!, history)
        assertEquals(null, state.getObject(g2Id))
        assertNotNull(state.getObject(g1Id))

        // Verify maxGroupDepth = 32 limit: creating depth 33 is rejected with MaxGroupDepthExceeded
        var currentGroup = g1Id
        for (depth in 2..32) {
            val nextGroup = ObjectId("g-depth-$depth")
            val p = ObjectPlanner.plan(
                state,
                ObjectAction.Group(targets = setOf(currentGroup), newGroupId = nextGroup),
                ctx.copy(gestureId = GestureId("g-depth-$depth"))
            )
            assertTrue(p.isSuccess, "Expected depth $depth to succeed")
            executePlan(state, p.getOrNull()!!)
            currentGroup = nextGroup
        }
        val depth33Plan = ObjectPlanner.plan(
            state,
            ObjectAction.Group(targets = setOf(currentGroup), newGroupId = ObjectId("g-depth-33")),
            ctx.copy(gestureId = GestureId("g-depth-33"))
        )
        assertTrue(depth33Plan.isFailure)
        assertTrue(depth33Plan.errorOrNull() is FeatureError.MaxGroupDepthExceeded)
    }

    @Test
    fun z_order() {
        val state = createBaseState()
        val s1 = makeStroke("s1", listOf(DocPoint(10.0, 10.0), DocPoint(20.0, 20.0)), zOrder = 1L)
        val s2 = makeStroke("s2", listOf(DocPoint(10.0, 10.0), DocPoint(20.0, 20.0)), zOrder = 2L)
        val s3 = makeStroke("s3", listOf(DocPoint(10.0, 10.0), DocPoint(20.0, 20.0)), zOrder = 3L)
        state.putResidentObjectDirect(s1)
        state.putResidentObjectDirect(s2)
        state.putResidentObjectDirect(s3)
        val ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)

        // Bring s1 to front -> zOrder > s3.zOrder
        val frontPlan = ObjectPlanner.plan(
            state,
            ObjectAction.ChangeZOrder(setOf(ObjectId("s1")), ZOrderCommand.BRING_TO_FRONT),
            ctx
        )
        assertTrue(frontPlan.isSuccess)
        executePlan(state, frontPlan.getOrNull()!!)
        assertTrue(state.getObject(ObjectId("s1"))!!.style.zOrder > state.getObject(ObjectId("s3"))!!.style.zOrder)

        // Send s3 to back -> zOrder < s2.zOrder
        val backPlan = ObjectPlanner.plan(
            state,
            ObjectAction.ChangeZOrder(setOf(ObjectId("s3")), ZOrderCommand.SEND_TO_BACK),
            ctx
        )
        assertTrue(backPlan.isSuccess)
        executePlan(state, backPlan.getOrNull()!!)
        assertTrue(state.getObject(ObjectId("s3"))!!.style.zOrder < state.getObject(ObjectId("s2"))!!.style.zOrder)

        // Add layer2 and move s2 to layer2
        executePlan(
            state,
            EditPlan(
                base = state.version,
                mutations = listOf(inkspace.core.mutation.AddLayer(LayerDescriptor(layer2, pageId, "Layer 2"))),
                gesture = GestureId("g-l2"),
                label = inkspace.core.id.UndoLabel("Add Layer 2")
            )
        )
        val moveLayerPlan = ObjectPlanner.plan(
            state,
            ObjectAction.MoveToLayer(setOf(ObjectId("s2")), targetLayerId = layer2),
            ctx
        )
        assertTrue(moveLayerPlan.isSuccess)
        executePlan(state, moveLayerPlan.getOrNull()!!)
        assertEquals(layer2, state.getObject(ObjectId("s2"))!!.layer)
    }

    @Test
    fun segment_interpolation() {
        val state = createBaseState()
        val history = History()
        // Horizontal stroke from x=0 to x=100 at y=50 with 5 samples: x = 0, 25, 50, 75, 100
        // Pressures: 0.2, 0.4, 0.6, 0.8, 1.0
        val s1 = makeStroke(
            id = "s-line",
            points = listOf(
                DocPoint(0.0, 50.0),
                DocPoint(25.0, 50.0),
                DocPoint(50.0, 50.0),
                DocPoint(75.0, 50.0),
                DocPoint(100.0, 50.0)
            )
        )
        // Non-stroke objects at x=50, y=50 (TextPayload, ShapePayload, ImagePayload)
        val txtObj = SceneObject(
            id = ObjectId("txt-1"),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(40.0, 40.0, 60.0, 60.0),
            payload = TextPayload("Protected Text")
        )
        val shapeObj = SceneObject(
            id = ObjectId("shape-1"),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(40.0, 40.0, 60.0, 60.0),
            payload = ShapePayload(ShapeType.Rectangle, strokeColor = 0xFF000000.toInt())
        )
        val imgObj = SceneObject(
            id = ObjectId("img-1"),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(40.0, 40.0, 60.0, 60.0),
            payload = ImagePayload(
                asset = inkspace.core.id.AssetId("a".repeat(64)),
                naturalWidthPx = 100,
                naturalHeightPx = 100
            )
        )
        state.putResidentObjectDirect(s1)
        state.putResidentObjectDirect(txtObj)
        state.putResidentObjectDirect(shapeObj)
        state.putResidentObjectDirect(imgObj)

        val ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)

        // 1. Segment Eraser at (50, 50) with radius 10.0 -> clips [40.0, 60.0] of s-line,
        //    leaving [0.0..40.0] and [60.0..100.0], and does NOT touch txt-1, shape-1, or img-1!
        val segErasePlan = ObjectPlanner.plan(
            state,
            ObjectAction.Erase(
                pathPoints = listOf(DocPoint(50.0, 30.0), DocPoint(50.0, 70.0)),
                spec = EraserSpec(
                    mode = EraserMode.SEGMENT,
                    shape = EraserShape.CIRCLE,
                    radiusPt = 10.0,
                    layerPolicy = EraserLayerPolicy.VISIBLE_UNLOCKED_LAYERS
                ),
                splitIdPrefix = "split-s1"
            ),
            ctx.copy(gestureId = GestureId("g-seg-erase"))
        )
        assertTrue(segErasePlan.isSuccess, "Expected segment erase plan to succeed: ${segErasePlan.errorOrNull()}")
        executePlan(state, segErasePlan.getOrNull()!!, history)

        // Original stroke removed, replaced by 2 split strokes
        assertEquals(null, state.getObject(ObjectId("s-line")))
        // Non-stroke objects untouched!
        assertNotNull(state.getObject(ObjectId("txt-1")))
        assertNotNull(state.getObject(ObjectId("shape-1")))
        assertNotNull(state.getObject(ObjectId("img-1")))

        val remainingStrokes = state.snapshot().orderedSceneObjects()
            .filter { it.payload is StrokePayload }
            .sortedBy { it.bounds.left }
        assertEquals(2, remainingStrokes.size, "Expected 2 split sub-strokes after middle segment erase")

        val leftStroke = remainingStrokes[0].payload as StrokePayload
        val rightStroke = remainingStrokes[1].payload as StrokePayload

        val leftEndWorldX = leftStroke.origin.x + leftStroke.samples.last().relX
        val rightStartWorldX = rightStroke.origin.x + rightStroke.samples.first().relX
        assertEquals(40.0, leftEndWorldX, 0.5, "Left split stroke must end at interpolated x=40.0")
        assertEquals(60.0, rightStartWorldX, 0.5, "Right split stroke must start at interpolated x=60.0")

        // Verify pressure was linearly interpolated at x=40 (between x=25 p=0.4 and x=50 p=0.6 -> t=0.6 -> p=0.52)
        // and at x=60 (between x=50 p=0.6 and x=75 p=0.8 -> t=0.4 -> p=0.68)
        assertEquals(0.52f, leftStroke.samples.last().pressure, 0.03f)
        assertEquals(0.68f, rightStroke.samples.first().pressure, 0.03f)

        // 2. Single Undo restores the original unbroken stroke s-line and removes the 2 split pieces!
        val undoPlan = history.undo(state.version)
        assertNotNull(undoPlan)
        executePlan(state, undoPlan)
        assertNotNull(state.getObject(ObjectId("s-line")))
        assertEquals(
            1,
            state.snapshot().orderedSceneObjects().count { it.payload is StrokePayload }
        )

        // 3. Object Eraser mode DOES remove non-stroke objects (txt-1, shape-1, img-1) and s-line
        val objErasePlan = ObjectPlanner.plan(
            state,
            ObjectAction.Erase(
                pathPoints = listOf(DocPoint(50.0, 50.0)),
                spec = EraserSpec(
                    mode = EraserMode.OBJECT,
                    shape = EraserShape.CIRCLE,
                    radiusPt = 15.0
                )
            ),
            ctx.copy(gestureId = GestureId("g-obj-erase"))
        )
        assertTrue(objErasePlan.isSuccess)
        executePlan(state, objErasePlan.getOrNull()!!, history)
        assertEquals(0, state.snapshot().orderedSceneObjects().size)
    }

    @Test
    fun clipboard_copy_cut_paste_remap() {
        val state = createBaseState()
        val s1 = makeStroke("s1", listOf(DocPoint(20.0, 20.0), DocPoint(40.0, 40.0)))
        val s2 = makeStroke("s2", listOf(DocPoint(60.0, 20.0), DocPoint(80.0, 40.0)))
        val conn = SceneObject(
            id = ObjectId("conn-1"),
            space = spaceId,
            layer = layer1,
            bounds = DocRect(40.0, 30.0, 60.0, 30.0),
            payload = ConnectorPayload(
                sourceId = ObjectId("s1"),
                targetId = ObjectId("s2"),
                strokeColor = 0xFF000000.toInt()
            )
        )
        state.putResidentObjectDirect(s1)
        state.putResidentObjectDirect(s2)
        state.putResidentObjectDirect(conn)

        val ctx = PlanContext(activeSpaceId = spaceId, activePageId = pageId, activeLayerId = layer1)
        val groupPlan = ObjectPlanner.plan(
            state,
            ObjectAction.Group(
                targets = setOf(ObjectId("s1"), ObjectId("s2"), ObjectId("conn-1")),
                newGroupId = ObjectId("g-clip")
            ),
            ctx
        )
        assertTrue(groupPlan.isSuccess)
        executePlan(state, groupPlan.getOrNull()!!)

        // Copy g-clip into a ClipboardBundle with TransferId
        val bundleOut = ObjectPlanner.copyToClipboard(
            read = state,
            targets = setOf(ObjectId("g-clip")),
            transferId = TransferId("tx-transfer-101")
        )
        assertTrue(bundleOut.isSuccess)
        val bundle = bundleOut.getOrNull()!!
        assertEquals(TransferId("tx-transfer-101"), bundle.transferId)
        assertEquals(4, bundle.frozenEntries.size) // g-clip + s1 + s2 + conn-1

        // Paste at (200, 200) -> all IDs, group members, and connector endpoints must be remapped!
        val pastePlan = ObjectPlanner.plan(
            state,
            ObjectAction.Paste(
                bundle = bundle,
                targetCenter = DocPoint(200.0, 200.0),
                idPrefix = "pasted-1"
            ),
            ctx.copy(gestureId = GestureId("g-paste"))
        )
        assertTrue(pastePlan.isSuccess)
        executePlan(state, pastePlan.getOrNull()!!)

        val allObjects = state.snapshot().orderedSceneObjects()
        assertEquals(8, allObjects.size)

        val pastedGroups = allObjects.filter { it.id != ObjectId("g-clip") && it.payload is GroupPayload }
        assertEquals(1, pastedGroups.size)
        val pastedGroupPayload = pastedGroups[0].payload as GroupPayload
        assertEquals(3, pastedGroupPayload.members.size)
        assertTrue(pastedGroupPayload.members.none { it.value in setOf("s1", "s2", "conn-1") })

        val pastedConnectors = allObjects.filter { it.id != ObjectId("conn-1") && it.payload is ConnectorPayload }
        assertEquals(1, pastedConnectors.size)
        val pastedConnPayload = pastedConnectors[0].payload as ConnectorPayload
        assertTrue(pastedConnPayload.sourceId in pastedGroupPayload.members)
        assertTrue(pastedConnPayload.targetId in pastedGroupPayload.members)
    }
}
