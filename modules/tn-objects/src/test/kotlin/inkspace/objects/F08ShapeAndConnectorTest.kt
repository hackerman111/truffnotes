package inkspace.objects

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.SpaceId
import inkspace.core.limits.Limits
import inkspace.core.model.DocumentKind
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.PageDescriptor
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.History
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.PlanContext
import inkspace.core.objects.RecognitionConfig
import inkspace.core.objects.ShapeCandidate
import inkspace.core.objects.StrokeData
import inkspace.core.outcome.Outcome
import inkspace.core.payload.AnchorSide
import inkspace.core.payload.ArrowheadStyle
import inkspace.core.payload.ConnectorAnchor
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.DanglingEndpointPolicy
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class F08ShapeAndConnectorTest {

    private val docId = DocumentId("doc-f08-test")
    private val spaceId = SpaceId("space-1")
    private val pageId = PageId("page-1")
    private val layer1 = LayerId("layer-1")

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

    private fun makeSampleStroke(
        points: List<DocPoint>,
        holdMs: Long = 500L
    ): StrokeData {
        val origin = points.firstOrNull() ?: DocPoint(0.0, 0.0)
        val samples = points.map { p ->
            inkspace.core.payload.StrokeSamplePoint(
                relX = (p.x - origin.x).toFloat(),
                relY = (p.y - origin.y).toFloat(),
                pressure = 0.5f,
                tilt = 0.0f,
                timeDeltaMs = 10
            )
        }
        return StrokeData(
            origin = origin,
            samples = samples,
            holdDurationMs = holdMs
        )
    }

    @Test
    fun testRecognizeStraightLineAndArrow() {
        // Straight line
        val linePts = (0..20).map { i -> DocPoint(100.0 + i * 10.0, 100.0 + i * 2.0) }
        val stroke = makeSampleStroke(linePts, holdMs = 500L)
        val candidate = ShapeRecognizer.recognize(stroke)
        assertTrue(candidate is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Line, candidate.shapeType)
        assertEquals(2, candidate.payload.vertices.size)

        // Arrow with terminal barb
        val arrowPts = ArrayList<DocPoint>()
        for (i in 0..20) arrowPts.add(DocPoint(100.0 + i * 10.0, 200.0))
        arrowPts.add(DocPoint(290.0, 190.0))
        arrowPts.add(DocPoint(280.0, 185.0))
        val arrowStroke = makeSampleStroke(arrowPts, holdMs = 600L)
        val arrowCandidate = ShapeRecognizer.recognize(arrowStroke)
        assertTrue(arrowCandidate is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Arrow, arrowCandidate.shapeType)
        assertEquals(ArrowheadStyle.OPEN_ARROW, arrowCandidate.payload.endArrowhead)
    }

    @Test
    fun testRecognizeCircleAndEllipse() {
        // Equidimensional Circle
        val circlePts = (0..36).map { i ->
            val a = (i.toDouble() / 36) * 2 * PI
            DocPoint(300.0 + 80.0 * cos(a), 300.0 + 80.0 * sin(a))
        }
        val circleStroke = makeSampleStroke(circlePts, holdMs = 500L)
        val circleCand = ShapeRecognizer.recognize(circleStroke)
        assertTrue(circleCand is ShapeCandidate.Recognized, "Expected recognized circle")
        assertEquals(ShapeType.Circle, circleCand.shapeType)

        // Elongated Ellipse
        val ellipsePts = (0..36).map { i ->
            val a = (i.toDouble() / 36) * 2 * PI
            DocPoint(300.0 + 120.0 * cos(a), 300.0 + 40.0 * sin(a))
        }
        val ellipseStroke = makeSampleStroke(ellipsePts, holdMs = 500L)
        val ellipseCand = ShapeRecognizer.recognize(ellipseStroke)
        assertTrue(ellipseCand is ShapeCandidate.Recognized, "Expected recognized ellipse")
        assertEquals(ShapeType.Ellipse, ellipseCand.shapeType)
    }

    @Test
    fun testRecognizeSquareRectangleTrianglePolygon() {
        // Triangle (3 corners)
        val triPts = ArrayList<DocPoint>()
        for (i in 0..10) triPts.add(DocPoint(200.0 + i * 10.0, 200.0))
        for (i in 0..10) triPts.add(DocPoint(300.0 - i * 5.0, 200.0 - i * 8.66))
        for (i in 0..10) triPts.add(DocPoint(250.0 - i * 5.0, 113.4 + i * 8.66))
        val triStroke = makeSampleStroke(triPts, holdMs = 500L)
        val triCand = ShapeRecognizer.recognize(triStroke)
        assertTrue(triCand is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Triangle, triCand.shapeType)

        // Rectangle (4 corners)
        val rectPts = ArrayList<DocPoint>()
        for (i in 0..15) rectPts.add(DocPoint(100.0 + i * 10.0, 100.0))
        for (i in 0..10) rectPts.add(DocPoint(250.0, 100.0 + i * 10.0))
        for (i in 0..15) rectPts.add(DocPoint(250.0 - i * 10.0, 200.0))
        for (i in 0..10) rectPts.add(DocPoint(100.0, 200.0 - i * 10.0))
        val rectStroke = makeSampleStroke(rectPts, holdMs = 500L)
        val rectCand = ShapeRecognizer.recognize(rectStroke)
        assertTrue(rectCand is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Rectangle, rectCand.shapeType)

        // Square (4 corners with ~1.0 aspect ratio)
        val squarePts = ArrayList<DocPoint>()
        for (i in 0..10) squarePts.add(DocPoint(100.0 + i * 10.0, 100.0))
        for (i in 0..10) squarePts.add(DocPoint(200.0, 100.0 + i * 10.0))
        for (i in 0..10) squarePts.add(DocPoint(200.0 - i * 10.0, 200.0))
        for (i in 0..10) squarePts.add(DocPoint(100.0, 200.0 - i * 10.0))
        val squareStroke = makeSampleStroke(squarePts, holdMs = 500L)
        val squareCand = ShapeRecognizer.recognize(squareStroke)
        assertTrue(squareCand is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Square, squareCand.shapeType)
    }

    @Test
    fun testRecognizeOpenPolylineArcAndCurve() {
        // Polyline (2 sharp corners)
        val polylinePts = ArrayList<DocPoint>()
        for (i in 0..10) polylinePts.add(DocPoint(100.0 + i * 10.0, 100.0))
        for (i in 0..10) polylinePts.add(DocPoint(200.0, 100.0 + i * 10.0))
        for (i in 0..10) polylinePts.add(DocPoint(200.0 + i * 10.0, 200.0))
        val polylineStroke = makeSampleStroke(polylinePts, holdMs = 500L)
        val polylineCand = ShapeRecognizer.recognize(polylineStroke)
        assertTrue(polylineCand is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Polyline, polylineCand.shapeType)

        // Circular arc (open)
        val arcPts = (0..20).map { i ->
            val a = (i.toDouble() / 20) * PI
            DocPoint(200.0 + 80.0 * cos(a), 200.0 + 80.0 * sin(a))
        }
        val arcStroke = makeSampleStroke(arcPts, holdMs = 500L)
        val arcCand = ShapeRecognizer.recognize(arcStroke)
        assertTrue(arcCand is ShapeCandidate.Recognized)
        assertEquals(ShapeType.Arc, arcCand.shapeType)
    }

    @Test
    fun testSubThresholdHoldAndDisabledConfigReject() {
        val linePts = (0..20).map { i -> DocPoint(100.0 + i * 10.0, 100.0) }

        // Sub-threshold hold (< 400ms)
        val quickStroke = makeSampleStroke(linePts, holdMs = 250L)
        val resQuick = ShapeRecognizer.recognize(quickStroke)
        assertTrue(resQuick is ShapeCandidate.Rejected)

        // Disabled holdToShapeEnabled
        val disabledConfig = RecognitionConfig(drawAndHoldEnabled = false)
        val stroke = makeSampleStroke(linePts, holdMs = 500L)
        val resDisabled = ShapeRecognizer.recognize(stroke, disabledConfig)
        assertTrue(resDisabled is ShapeCandidate.Rejected)
    }

    @Test
    fun testAmbiguousHandwritingCorpusVerification() {
        val rnd = Random(20261105)
        var ambiguousCount = 0
        val totalNegatives = 2200

        // Simulate 2200 negative samples: cursive words with loops ('e', 'o', 'g'), math symbols, sketches
        for (i in 0 until totalNegatives) {
            val pts = ArrayList<DocPoint>()
            val sampleType = i % 4
            when (sampleType) {
                0 -> {
                    // Cursive handwriting word with multiple self-intersecting loops
                    for (step in 0..30) {
                        val t = step.toDouble()
                        val x = 100.0 + t * 5.0 + 10.0 * sin(t * 0.8)
                        val y = 100.0 + 20.0 * sin(t * 1.5) + (if (step in 10..15) 15.0 * cos(t) else 0.0)
                        pts.add(DocPoint(x, y))
                    }
                }
                1 -> {
                    // Math equation integral / sigma / square root
                    pts.add(DocPoint(50.0, 50.0))
                    pts.add(DocPoint(55.0, 40.0))
                    pts.add(DocPoint(55.0, 120.0))
                    pts.add(DocPoint(60.0, 130.0))
                    pts.add(DocPoint(70.0, 80.0))
                    pts.add(DocPoint(120.0, 80.0))
                }
                2 -> {
                    // Freehand organic sketching / shading zig-zag with noise
                    for (step in 0..25) {
                        pts.add(DocPoint(200.0 + (step % 2) * 20.0 + rnd.nextDouble() * 3.0, 200.0 + step * 3.0))
                    }
                }
                3 -> {
                    // Small dot / tiny scribble
                    for (step in 0..5) {
                        pts.add(DocPoint(10.0 + rnd.nextDouble() * 2.0, 10.0 + rnd.nextDouble() * 2.0))
                    }
                }
            }
            val stroke = makeSampleStroke(pts, holdMs = 500L)
            val cand = ShapeRecognizer.recognize(stroke)
            if (cand is ShapeCandidate.AmbiguousKeepOriginal || cand is ShapeCandidate.Rejected) {
                ambiguousCount++
            } else {
                println("MISCLASSIFIED sampleType=$sampleType cand=$cand")
            }
        }

        val preservationRate = ambiguousCount.toDouble() / totalNegatives.toDouble()
        assertTrue(
            preservationRate >= 0.995,
            "False positive rate must be <= 0.5% (got preservation rate $preservationRate on $totalNegatives samples)"
        )
    }

    @Test
    fun testShapePlannerCreateUpdateAppearanceAndVertices() {
        val state = createBaseState()
        val shapeId = ObjectId("shape-rect-1")
        val bounds = DocRect(100.0, 100.0, 200.0, 200.0)
        val initialPayload = ShapePayload(
            shapeType = ShapeType.Rectangle,
            strokeColor = 0xFF0000FF.toInt(),
            strokeWidthPt = 3.0,
            fillColor = null
        )

        // 1. Create shape
        val createAction = ObjectAction.ShapeAction.CreateShape(
            objectId = shapeId,
            payload = initialPayload,
            bounds = bounds
        )
        val plan = ShapePlanner.plan(state, createAction).getOrNull()!!
        executePlan(state, plan)
        val created = state.getObject(shapeId)
        assertNotNull(created)
        assertEquals(ShapeType.Rectangle, (created.payload as ShapePayload).shapeType)

        // 2. Update appearance (fill, stroke color, arrowheads)
        val updateAppAction = ObjectAction.ShapeAction.UpdateShapeAppearance(
            targetId = shapeId,
            strokeColor = 0xFFFF0000.toInt(),
            fillColor = 0x8000FF00.toInt(),
            strokeWidthPt = 5.0,
            cornerRadiusPt = 8.0
        )
        val appPlan = ShapePlanner.plan(state, updateAppAction).getOrNull()!!
        executePlan(state, appPlan)
        val updated = state.getObject(shapeId)
        assertNotNull(updated)
        val p = updated.payload as ShapePayload
        assertEquals(0xFFFF0000.toInt(), p.strokeColor)
        assertEquals(0x8000FF00.toInt(), p.fillColor)
        assertEquals(5.0, p.strokeWidthPt)
        assertEquals(8.0, p.cornerRadiusPt)

        // 3. Update vertices
        val newVertices = listOf(DocPoint(120.0, 120.0), DocPoint(220.0, 220.0))
        val updateVertAction = ObjectAction.ShapeAction.UpdateShapeVertices(
            targetId = shapeId,
            vertices = newVertices
        )
        val vertPlan = ShapePlanner.plan(state, updateVertAction).getOrNull()!!
        executePlan(state, vertPlan)
        val vertObj = state.getObject(shapeId)
        assertNotNull(vertObj)
        assertEquals(2, (vertObj.payload as ShapePayload).vertices.size)
    }

    @Test
    fun testConnectorAnchorsFollowingAndDanglingPolicy() {
        val state = createBaseState()
        val history = History()

        // Create two shapes
        val shape1Id = ObjectId("shape-1")
        val shape2Id = ObjectId("shape-2")
        val rect1 = DocRect(100.0, 100.0, 200.0, 200.0)
        val rect2 = DocRect(400.0, 100.0, 500.0, 200.0)
        executePlan(
            state,
            ShapePlanner.plan(
                state,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = shape1Id,
                    payload = ShapePayload(ShapeType.Rectangle, 0xFF000000.toInt(), null, 2.0),
                    bounds = rect1
                ),
                PlanContext(gestureId = GestureId("create-s1"))
            ).getOrNull()!!,
            history
        )
        executePlan(
            state,
            ShapePlanner.plan(
                state,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = shape2Id,
                    payload = ShapePayload(ShapeType.Rectangle, 0xFF000000.toInt(), null, 2.0),
                    bounds = rect2
                ),
                PlanContext(gestureId = GestureId("create-s2"))
            ).getOrNull()!!,
            history
        )

        // Create connector between shape1 RIGHT and shape2 LEFT
        val connectorId = ObjectId("conn-1")
        val connPayload = ConnectorPayload(
            sourceId = shape1Id,
            targetId = shape2Id,
            strokeColor = 0xFF000000.toInt(),
            sourceAnchor = ConnectorAnchor(side = AnchorSide.RIGHT),
            targetAnchor = ConnectorAnchor(side = AnchorSide.LEFT),
            danglingPolicy = DanglingEndpointPolicy.DELETE_CONNECTOR
        )
        executePlan(
            state,
            ShapePlanner.plan(
                state,
                ObjectAction.ShapeAction.CreateConnector(
                    connectorId = connectorId,
                    sourceId = shape1Id,
                    targetId = shape2Id,
                    sourceAnchor = connPayload.sourceAnchor,
                    targetAnchor = connPayload.targetAnchor,
                    strokeColor = 0xFF000000.toInt(),
                    danglingPolicy = DanglingEndpointPolicy.DELETE_CONNECTOR
                ),
                PlanContext(gestureId = GestureId("create-conn"))
            ).getOrNull()!!,
            history
        )

        val connObj = state.getObject(connectorId)
        assertNotNull(connObj)
        val cp = connObj.payload as ConnectorPayload
        assertEquals(DocPoint(200.0, 150.0), cp.resolvedStartPoint)
        assertEquals(DocPoint(400.0, 150.0), cp.resolvedEndPoint)

        // Move shape 1 by (+50, +50)
        executePlan(
            state,
            TransformPlanner.planMove(state, ObjectAction.Move(setOf(shape1Id), 50.0, 50.0), PlanContext(gestureId = GestureId("move-s1"))).getOrNull()!!,
            history
        )

        // Connector resolved start point must follow shape 1 move!
        val connMoved = state.getObject(connectorId)
        assertNotNull(connMoved)
        val cpMoved = connMoved.payload as ConnectorPayload
        assertEquals(DocPoint(250.0, 200.0), cpMoved.resolvedStartPoint)
        assertEquals(DocPoint(400.0, 150.0), cpMoved.resolvedEndPoint)

        // Delete shape 1 -> Connector with DELETE_CONNECTOR policy must be deleted in same plan!
        executePlan(
            state,
            ObjectPlanner.plan(state, ObjectAction.Delete(setOf(shape1Id)), PlanContext(gestureId = GestureId("delete-s1"))).getOrNull()!!,
            history
        )
        assertNull(state.getObject(shape1Id))
        assertNull(state.getObject(connectorId), "Connector should be deleted with shape1")

        // Undo deletion
        val undoPlan1 = history.undo(state.version)
        assertNotNull(undoPlan1)
        executePlan(state, undoPlan1)
        assertNotNull(state.getObject(shape1Id))
        assertNotNull(state.getObject(connectorId))
    }

    @Test
    fun testQuickDiagramAtomicTransaction() {
        val state = createBaseState()
        val history = History()

        val srcId = ObjectId("src-box")
        executePlan(
            state,
            ShapePlanner.plan(
                state,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = srcId,
                    payload = ShapePayload(ShapeType.Rectangle, 0xFF000000.toInt(), null, 2.0),
                    bounds = DocRect(50.0, 50.0, 150.0, 150.0)
                ),
                PlanContext(gestureId = GestureId("create-src"))
            ).getOrNull()!!,
            history
        )

        // Quick diagram create + connect in 1 atomic plan
        val newShapeId = ObjectId("new-box")
        val connId = ObjectId("quick-conn")
        val quickAction = ObjectAction.ShapeAction.QuickDiagramCreateAndConnect(
            sourceId = srcId,
            newShapeId = newShapeId,
            newShapePayload = ShapePayload(ShapeType.Circle, 0xFF112233.toInt(), null, 2.0),
            newShapeBounds = DocRect(300.0, 50.0, 400.0, 150.0),
            connectorId = connId,
            sourceAnchor = ConnectorAnchor(side = AnchorSide.RIGHT),
            targetAnchor = ConnectorAnchor(side = AnchorSide.LEFT)
        )

        val plan = ShapePlanner.plan(state, quickAction, PlanContext(gestureId = GestureId("quick-diag"))).getOrNull()!!
        executePlan(state, plan, history)

        assertNotNull(state.getObject(newShapeId))
        assertNotNull(state.getObject(connId))

        // Single undo step restores document to state prior to quick diagram
        val undoPlan2 = history.undo(state.version)
        assertNotNull(undoPlan2)
        executePlan(state, undoPlan2)
        assertNull(state.getObject(newShapeId))
        assertNull(state.getObject(connId))
        assertNotNull(state.getObject(srcId))
    }
}
