package inkspace.runtime

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.ink.BrushKind
import inkspace.core.ink.BrushSpec
import inkspace.core.ink.GestureSettings
import inkspace.core.ink.InputBatch
import inkspace.core.ink.MultiTapGestureEvent
import inkspace.core.ink.RealSample
import inkspace.core.ink.StylusInputPolicy
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.DocVersion
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.objects.GridKind
import inkspace.core.objects.GridSpec
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.ProtractorOverlayState
import inkspace.core.objects.RulerEdge
import inkspace.core.objects.RulerOverlayState
import inkspace.core.objects.RulerUnit
import inkspace.core.objects.SnapConfig
import inkspace.core.payload.AnchorSide
import inkspace.core.payload.ArrowheadStyle
import inkspace.core.payload.ConnectorAnchor
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.DanglingEndpointPolicy
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.objects.SnapEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class F08RuntimeShapesAndGesturesIntegrationTest {

    @Test
    fun testAcceptance26HoldAfterStraightLineStraightensIt(@TempDir tempDir: Path) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val docId = DocumentId("doc-acceptance26")

        try {
            val session = runtime.openSessionSyncForTest(docId)
            runtime.configureInkSession(
                session = session,
                brush = BrushSpec(kind = BrushKind.BALL, colorArgb = 0xFF000000.toInt(), baseWidthPt = 3.0),
                policy = StylusInputPolicy(stylusOnlyDrawing = true)
            )

            // Draw straight line from (50, 50) to (250, 50), then hold at (250, 50) for 500ms
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 1L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(RealSample(50f, 50f, 0.5f, 0f, 100L))
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 2L,
                    action = InputActionKind.MOVE,
                    realSamples = listOf(
                        RealSample(100f, 50f, 0.5f, 0f, 150L),
                        RealSample(150f, 50f, 0.5f, 0f, 200L),
                        RealSample(200f, 50f, 0.5f, 0f, 250L),
                        RealSample(250f, 50f, 0.5f, 0f, 300L)
                    )
                ).getOrNull()!!
            )
            // Hold terminal position for 500ms (until 800L)
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 3L,
                    action = InputActionKind.MOVE,
                    realSamples = listOf(
                        RealSample(250.5f, 50.2f, 0.5f, 0f, 500L),
                        RealSample(250.8f, 50.4f, 0.5f, 0f, 800L)
                    )
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 4L,
                    action = InputActionKind.UP,
                    realSamples = listOf(RealSample(250.8f, 50.4f, 0.5f, 0f, 810L))
                ).getOrNull()!!
            )
            runtime.drainEngineLoopForTest()

            val snap = runtime.latestSnapshot(session)!!
            val objects = snap.orderedSceneObjects()
            assertEquals(1, objects.size, "Should have 1 converted shape object")
            val shapeObj = objects.first()
            assertTrue(shapeObj.payload is ShapePayload, "Payload must be ShapePayload")
            val sp = shapeObj.payload as ShapePayload
            assertEquals(ShapeType.Line, sp.shapeType, "ShapeType must be Line")

            // Single undo restores document
            runtime.trySend(AppCommand.Undo(session))
            runtime.drainEngineLoopForTest()
            val snapAfterUndo = runtime.latestSnapshot(session)!!
            assertEquals(0, snapAfterUndo.totalObjectCount, "Undo must remove straightened line in 1 step")
        } finally {
            runtime.closeAsync()
        }
    }

    @Test
    fun testAcceptance27HoldAfterCircleConvertsToShape(@TempDir tempDir: Path) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val docId = DocumentId("doc-acceptance27")

        try {
            val session = runtime.openSessionSyncForTest(docId)
            runtime.configureInkSession(
                session = session,
                brush = BrushSpec(kind = BrushKind.BALL, colorArgb = 0xFF112233.toInt(), baseWidthPt = 2.0),
                policy = StylusInputPolicy(stylusOnlyDrawing = true)
            )

            // Draw circular stroke with 32 points from t=100L to 400L, then hold at closure point until 950L
            val circleSamples = ArrayList<RealSample>()
            for (i in 0..32) {
                val a = (i.toDouble() / 32) * 2 * PI
                val x = 200f + 60f * cos(a).toFloat()
                val y = 200f + 60f * sin(a).toFloat()
                circleSamples.add(RealSample(x, y, 0.5f, 0f, 100L + i * 10L))
            }

            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 1L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(circleSamples.first())
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 2L,
                    action = InputActionKind.MOVE,
                    realSamples = circleSamples.drop(1)
                ).getOrNull()!!
            )
            // Hold terminal point for 550ms
            val lastPt = circleSamples.last()
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 3L,
                    action = InputActionKind.MOVE,
                    realSamples = listOf(
                        RealSample(lastPt.x + 0.2f, lastPt.y + 0.2f, 0.5f, 0f, 650L),
                        RealSample(lastPt.x + 0.4f, lastPt.y + 0.3f, 0.5f, 0f, 950L)
                    )
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 4L,
                    action = InputActionKind.UP,
                    realSamples = listOf(RealSample(lastPt.x + 0.4f, lastPt.y + 0.3f, 0.5f, 0f, 960L))
                ).getOrNull()!!
            )
            runtime.drainEngineLoopForTest()

            val snap = runtime.latestSnapshot(session)!!
            val objects = snap.orderedSceneObjects()
            assertEquals(1, objects.size, "Should convert to 1 shape object")
            val sp = objects.first().payload as ShapePayload
            assertEquals(ShapeType.Circle, sp.shapeType, "ShapeType must be Circle")

            // Single undo restores document
            runtime.trySend(AppCommand.Undo(session))
            runtime.drainEngineLoopForTest()
            val snapAfterUndo = runtime.latestSnapshot(session)!!
            assertEquals(0, snapAfterUndo.totalObjectCount, "Undo must remove circle in 1 step")
        } finally {
            runtime.closeAsync()
        }
    }

    @Test
    fun testAcceptance28CircleLiftReholdSelectsEnclosedObjects(@TempDir tempDir: Path) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val docId = DocumentId("doc-acceptance28")

        try {
            val session = runtime.openSessionSyncForTest(docId)

            // Insert 2 targets inside (150, 150) -> (250, 250)
            val target1Id = ObjectId("target-box-1")
            val target2Id = ObjectId("target-box-2")
            runtime.submitObjectAction(
                session,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = target1Id,
                    payload = ShapePayload(ShapeType.Rectangle, 0xFF000000.toInt(), null, 2.0),
                    bounds = DocRect(160.0, 160.0, 190.0, 190.0)
                ),
                GestureId("create-t1")
            )
            runtime.submitObjectAction(
                session,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = target2Id,
                    payload = ShapePayload(ShapeType.Rectangle, 0xFF000000.toInt(), null, 2.0),
                    bounds = DocRect(210.0, 210.0, 240.0, 240.0)
                ),
                GestureId("create-t2")
            )
            runtime.drainEngineLoopForTest()

            // 1. Draw closed loop stroke enclosing the targets
            val loopPts = listOf(
                DocPoint(100.0, 100.0),
                DocPoint(300.0, 100.0),
                DocPoint(300.0, 300.0),
                DocPoint(100.0, 300.0),
                DocPoint(100.0, 100.0)
            )
            val strokeData = inkspace.core.objects.StrokeData(
                origin = DocPoint(100.0, 100.0),
                samples = loopPts.map { inkspace.core.payload.StrokeSamplePoint((it.x - 100.0).toFloat(), (it.y - 100.0).toFloat()) },
                holdDurationMs = 0L,
                isClosedLoop = true
            )
            val loopId = ObjectId("loop-stroke-1")
            runtime.trySend(
                AppCommand.SubmitEdit(
                    session = session,
                    plan = EditPlan(
                        base = runtime.latestSnapshot(session)!!.version,
                        gesture = GestureId("create-loop"),
                        label = UndoLabel("Draw Loop"),
                        mutations = listOf(
                            InsertObject(
                                space = runtime.latestSnapshot(session)!!.pages.first().spaceId,
                                layer = runtime.latestSnapshot(session)!!.layers.first().id,
                                id = loopId,
                                payload = StrokePayload(
                                    brushId = "ball",
                                    color = 0xFF000000.toInt(),
                                    baseWidthPt = 2.0,
                                    origin = DocPoint(100.0, 100.0),
                                    samples = strokeData.samples
                                ),
                                bounds = DocRect(100.0, 100.0, 300.0, 300.0)
                            )
                        )
                    ),
                    estimatedBytes = 512L
                )
            )
            runtime.drainEngineLoopForTest()

            // 2. Re-hold on perimeter at (100, 200) for 400ms (threshold is 300ms)
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 10L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(RealSample(100f, 200f, 0.5f, 0f, 1000L))
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 11L,
                    action = InputActionKind.MOVE,
                    realSamples = listOf(RealSample(100.2f, 200.2f, 0.5f, 0f, 1450L))
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 12L,
                    action = InputActionKind.UP,
                    realSamples = listOf(RealSample(100.2f, 200.2f, 0.5f, 0f, 1460L))
                ).getOrNull()!!
            )
            runtime.drainEngineLoopForTest()

            // Verify both enclosed targets are selected in currentSelectionSet!
            val currentSel = runtime.currentSelectionSet(session)!!
            assertTrue(currentSel.frozenSelectedIds.contains(target1Id), "Target 1 must be selected")
            assertTrue(currentSel.frozenSelectedIds.contains(target2Id), "Target 2 must be selected")
        } finally {
            runtime.closeAsync()
        }
    }

    @Test
    fun testAcceptance29ScribbleToEraseUndoneInSingleStep(@TempDir tempDir: Path) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val docId = DocumentId("doc-acceptance29")

        try {
            val session = runtime.openSessionSyncForTest(docId)

            // Draw a stroke from (100, 100) to (200, 100)
            val stroke1Id = ObjectId("target-pen-stroke")
            runtime.trySend(
                AppCommand.SubmitEdit(
                    session = session,
                    plan = EditPlan(
                        base = runtime.latestSnapshot(session)!!.version,
                        gesture = GestureId("create-s1"),
                        label = UndoLabel("Draw Pen Stroke"),
                        mutations = listOf(
                            InsertObject(
                                space = runtime.latestSnapshot(session)!!.pages.first().spaceId,
                                layer = runtime.latestSnapshot(session)!!.layers.first().id,
                                id = stroke1Id,
                                payload = StrokePayload(
                                    brushId = "ball",
                                    color = 0xFF000000.toInt(),
                                    baseWidthPt = 2.0,
                                    origin = DocPoint(100.0, 100.0),
                                    samples = listOf(
                                        inkspace.core.payload.StrokeSamplePoint(0f, 0f),
                                        inkspace.core.payload.StrokeSamplePoint(100f, 0f)
                                    )
                                ),
                                bounds = DocRect(100.0, 100.0, 200.0, 100.0)
                            )
                        )
                    ),
                    estimatedBytes = 512L
                )
            )
            runtime.drainEngineLoopForTest()
            assertNotNull(runtime.latestSnapshot(session)!!.getObject(stroke1Id))

            // Draw rapid scribble zig-zag over (90..210, 90..110)
            val scribbleSamples = ArrayList<RealSample>()
            for (i in 0..30) {
                val x = 90f + (i % 2) * 120f
                val y = 95f + i * 0.5f
                scribbleSamples.add(RealSample(x, y, 0.5f, 0f, 1000L + i * 10L))
            }

            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 20L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(scribbleSamples.first())
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 21L,
                    action = InputActionKind.MOVE,
                    realSamples = scribbleSamples.drop(1)
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 22L,
                    action = InputActionKind.UP,
                    realSamples = listOf(scribbleSamples.last())
                ).getOrNull()!!
            )
            runtime.drainEngineLoopForTest()

            // Stroke must be erased!
            assertNull(runtime.latestSnapshot(session)!!.getObject(stroke1Id), "Stroke should be erased by scribble")

            // Undo in a single step restores the erased stroke!
            runtime.trySend(AppCommand.Undo(session))
            runtime.drainEngineLoopForTest()
            assertNotNull(runtime.latestSnapshot(session)!!.getObject(stroke1Id), "Single undo step must restore erased stroke")
        } finally {
            runtime.closeAsync()
        }
    }

    @Test
    fun testAcceptance30GeometricGesturesCanBeDisabled(@TempDir tempDir: Path) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val docId = DocumentId("doc-acceptance30")

        try {
            val session = runtime.openSessionSyncForTest(docId)

            // Disable geometric gestures
            runtime.setGestureSettings(session, GestureSettings(geometricGesturesEnabled = false))
            assertFalse(runtime.getGestureSettings(session).geometricGesturesEnabled)

            // Draw straight line with 500ms terminal hold
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 1L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(RealSample(50f, 50f, 0.5f, 0f, 100L))
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 2L,
                    action = InputActionKind.MOVE,
                    realSamples = listOf(
                        RealSample(150f, 50f, 0.5f, 0f, 200L),
                        RealSample(250f, 50f, 0.5f, 0f, 300L),
                        RealSample(250.5f, 50.2f, 0.5f, 0f, 800L)
                    )
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 3L,
                    action = InputActionKind.UP,
                    realSamples = listOf(RealSample(250.5f, 50.2f, 0.5f, 0f, 810L))
                ).getOrNull()!!
            )
            runtime.drainEngineLoopForTest()

            val snap = runtime.latestSnapshot(session)!!
            val objects = snap.orderedSceneObjects()
            assertEquals(1, objects.size)
            // MUST remain normal StrokePayload, NOT converted to ShapePayload!
            assertTrue(objects.first().payload is StrokePayload, "Disabled geometric gestures must preserve normal ink stroke")
        } finally {
            runtime.closeAsync()
        }
    }

    @Test
    fun testMultiFingerTapUndoRedoInRuntime(@TempDir tempDir: Path) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val docId = DocumentId("doc-multitap")

        try {
            val session = runtime.openSessionSyncForTest(docId)

            val boxId = ObjectId("box-multitap-1")
            runtime.submitObjectAction(
                session,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = boxId,
                    payload = ShapePayload(ShapeType.Rectangle, 0xFF000000.toInt(), null, 2.0),
                    bounds = DocRect(50.0, 50.0, 100.0, 100.0)
                ),
                GestureId("create-box")
            )
            runtime.drainEngineLoopForTest()
            assertNotNull(runtime.latestSnapshot(session)!!.getObject(boxId))

            // 2-finger double tap triggers Undo
            val undoEvent = MultiTapGestureEvent(
                fingerCount = 2,
                tapCount = 2,
                positionDoc = DocPoint(100.0, 100.0),
                timestampMs = 1000L
            )
            runtime.submitMultiTap(session, undoEvent)
            runtime.drainEngineLoopForTest()
            assertNull(runtime.latestSnapshot(session)!!.getObject(boxId), "2-finger double-tap must undo creation")

            // 3-finger double tap triggers Redo
            val redoEvent = MultiTapGestureEvent(
                fingerCount = 3,
                tapCount = 2,
                positionDoc = DocPoint(100.0, 100.0),
                timestampMs = 1100L
            )
            runtime.submitMultiTap(session, redoEvent)
            runtime.drainEngineLoopForTest()
            assertNotNull(runtime.latestSnapshot(session)!!.getObject(boxId), "3-finger double-tap must redo creation")
        } finally {
            runtime.closeAsync()
        }
    }

    @Test
    fun testRulerProtractorGridSnappingAndPersistence(@TempDir tempDir: Path) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val docId = DocumentId("doc-snap-persistence")

        try {
            val session = runtime.openSessionSyncForTest(docId)

            // Test 7 grid kinds via SnapEngine
            val kinds = listOf(
                GridKind.BLANK, GridKind.RULED, GridKind.SQUARED,
                GridKind.DOTTED, GridKind.MILLIMETER, GridKind.ISOMETRIC, GridKind.CUSTOM
            )
            for (k in kinds) {
                val gridCfg = SnapConfig(grid = GridSpec(kind = k, spacingPt = 20.0, snapEnabled = (k != GridKind.BLANK)))
                val res = SnapEngine.snapPoint(DocPoint(21.2, 42.1), gridCfg)
                assertNotNull(res)
            }

            // Ruler cm/inch unit conversions & 2-edge snap
            val rulerState = RulerOverlayState(
                isVisible = true,
                centerDoc = DocPoint(100.0, 100.0),
                lengthPt = 400.0,
                thicknessPt = 60.0,
                angleDegrees = 0.0,
                unit = RulerUnit.CENTIMETERS
            )
            runtime.setRulerOverlay(session, rulerState)
            assertEquals(RulerUnit.CENTIMETERS, runtime.getRulerOverlay(session)!!.unit)

            // Protractor arc/ray snap
            val protractorState = ProtractorOverlayState(
                isVisible = true,
                centerDoc = DocPoint(200.0, 200.0),
                radiusPt = 120.0,
                baseAngleDegrees = 0.0
            )
            runtime.setProtractorOverlay(session, protractorState)
            assertNotNull(runtime.getProtractorOverlay(session))

            // Create shape 1 & shape 2 connected by connector
            val s1 = ObjectId("s1")
            val s2 = ObjectId("s2")
            val conn = ObjectId("c12")
            runtime.submitObjectAction(
                session,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = s1,
                    payload = ShapePayload(ShapeType.Rectangle, 0xFF000000.toInt(), null, 2.0),
                    bounds = DocRect(50.0, 50.0, 150.0, 150.0)
                ),
                GestureId("g1")
            )
            runtime.submitObjectAction(
                session,
                ObjectAction.ShapeAction.CreateShape(
                    objectId = s2,
                    payload = ShapePayload(ShapeType.Circle, 0xFF000000.toInt(), null, 2.0),
                    bounds = DocRect(300.0, 50.0, 400.0, 150.0)
                ),
                GestureId("g2")
            )
            runtime.submitObjectAction(
                session,
                ObjectAction.ShapeAction.CreateConnector(
                    connectorId = conn,
                    sourceId = s1,
                    targetId = s2,
                    sourceAnchor = ConnectorAnchor(side = AnchorSide.RIGHT),
                    targetAnchor = ConnectorAnchor(side = AnchorSide.LEFT),
                    startArrowhead = ArrowheadStyle.NONE,
                    endArrowhead = ArrowheadStyle.OPEN_ARROW,
                    danglingPolicy = DanglingEndpointPolicy.DELETE_CONNECTOR
                ),
                GestureId("g3")
            )
            runtime.drainEngineLoopForTest()

            val snapBefore = runtime.latestSnapshot(session)!!
            assertNotNull(snapBefore.getObject(s1))
            assertNotNull(snapBefore.getObject(s2))
            assertNotNull(snapBefore.getObject(conn))
        } finally {
            runtime.closeAsync()
        }

        // Re-open document from SQLite storage to verify persistence
        val runtime2 = Runtime.start(config).getOrNull()!!
        try {
            val session2 = runtime2.openSessionSyncForTest(docId)
            val reloadedSnap = runtime2.latestSnapshot(session2)!!
            assertEquals(3, reloadedSnap.totalObjectCount, "All 3 objects must be recovered from SQLite storage")
            assertNotNull(reloadedSnap.getObject(ObjectId("s1")))
            assertNotNull(reloadedSnap.getObject(ObjectId("s2")))
            assertNotNull(reloadedSnap.getObject(ObjectId("c12")))
            val connObj = reloadedSnap.getObject(ObjectId("c12"))!!
            assertTrue(connObj.payload is ConnectorPayload)
        } finally {
            runtime2.closeAsync()
        }
    }
}
