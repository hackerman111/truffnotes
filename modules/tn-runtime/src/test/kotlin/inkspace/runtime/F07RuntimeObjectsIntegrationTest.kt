package inkspace.runtime

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.ObjectId
import inkspace.core.id.UndoLabel
import inkspace.core.ink.BrushKind
import inkspace.core.ink.BrushSpec
import inkspace.core.ink.InputBatch
import inkspace.core.ink.RealSample
import inkspace.core.ink.StylusInputPolicy
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.ObjectStyle
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.objects.EraserLayerPolicy
import inkspace.core.objects.EraserMode
import inkspace.core.objects.EraserShape
import inkspace.core.objects.EraserSpec
import inkspace.core.objects.FlipAxis
import inkspace.core.objects.ObjectAction
import inkspace.core.objects.ObjectKindFilter
import inkspace.core.objects.SelectionCombinator
import inkspace.core.objects.SelectionFilter
import inkspace.core.objects.SelectionMode
import inkspace.core.objects.SelectionQuery
import inkspace.core.objects.SelectionRule
import inkspace.core.objects.StylePatch
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.TextPayload
import inkspace.core.payload.TextRun
import inkspace.core.render.RenderReply
import inkspace.core.render.RenderRequest
import inkspace.core.render.Scene
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.render.Renderer
import inkspace.testsupport.FakeResourcePort
import inkspace.testsupport.FakeTextPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class F07RuntimeObjectsIntegrationTest {

    @Test
    fun end_to_end_lasso_style_move_group_segment_eraser_clipboard_and_vector_persistence(
        @TempDir tempDir: Path
    ) {
        val storageRoot = tempDir.toFile().absolutePath
        val config = RuntimeConfig(storageRootPath = storageRoot)
        val runtime = Runtime.start(config).getOrNull()!!
        val renderer = Renderer(
            geometryPort = runtime.defaultGeometryPort,
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
        val docId = DocumentId("doc-f07-e2e")

        val expectedStrokeCountAfterEdits: Int
        val expectedTextId = ObjectId("text-f07-1")

        try {
            val session = runtime.openSessionSyncForTest(docId)
            runtime.configureInkSession(
                session = session,
                brush = BrushSpec(
                    kind = BrushKind.BALL,
                    colorArgb = 0xFF1D4ED8.toInt(),
                    baseWidthPt = 2.5,
                    pressureSensitivity = 0.5f,
                    tiltSensitivity = 0.0f
                ),
                policy = StylusInputPolicy(stylusOnlyDrawing = true)
            )

            // 1. Write two stylus strokes via submitInkBatch
            // Stroke 1: horizontal from (20, 30) to (120, 30)
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 1L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(RealSample(20f, 30f, 0.5f, 0.0f, 100L))
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 2L,
                    action = InputActionKind.MOVE,
                    realSamples = listOf(
                        RealSample(45f, 30f, 0.6f, 0.0f, 105L),
                        RealSample(70f, 30f, 0.7f, 0.0f, 110L),
                        RealSample(95f, 30f, 0.6f, 0.0f, 115L)
                    )
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 3L,
                    action = InputActionKind.UP,
                    realSamples = listOf(RealSample(120f, 30f, 0.5f, 0.0f, 120L))
                ).getOrNull()!!
            )

            // Stroke 2: horizontal from (20, 60) to (120, 60)
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 4L,
                    action = InputActionKind.DOWN,
                    realSamples = listOf(RealSample(20f, 60f, 0.5f, 0.0f, 200L))
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 5L,
                    action = InputActionKind.MOVE,
                    realSamples = listOf(
                        RealSample(50f, 60f, 0.6f, 0.0f, 205L),
                        RealSample(90f, 60f, 0.6f, 0.0f, 210L)
                    )
                ).getOrNull()!!
            )
            runtime.submitInkBatch(
                InputBatch.fromSamples(
                    session = session,
                    pointerId = 0,
                    sequenceNumber = 6L,
                    action = InputActionKind.UP,
                    realSamples = listOf(RealSample(120f, 60f, 0.5f, 0.0f, 215L))
                ).getOrNull()!!
            )
            runtime.drainEngineLoopForTest()

            val snapInitial = runtime.latestSnapshot(session)!!
            val initialStrokes = snapInitial.orderedSceneObjects().filter { it.payload is StrokePayload }
            assertEquals(2, initialStrokes.size, "Two strokes must be committed")
            val stroke1Id = initialStrokes.minBy { it.bounds.top }.id
            val stroke2Id = initialStrokes.maxBy { it.bounds.top }.id

            // Add a Textbox object to verify mixed selection preserves separate Textbox objects
            val spaceId = snapInitial.pages.first().spaceId
            val layerId = snapInitial.layers.first().id
            runtime.trySend(
                AppCommand.SubmitEdit(
                    session = session,
                    plan = EditPlan(
                        base = snapInitial.version,
                        gesture = GestureId("g-add-text"),
                        label = UndoLabel("Add Textbox"),
                        mutations = listOf(
                            InsertObject(
                                space = spaceId,
                                layer = layerId,
                                id = expectedTextId,
                                payload = TextPayload(
                                    text = "Handwriting + Textbox",
                                    fontSizePt = 12.0,
                                    fontColor = 0xFF111827.toInt(),
                                    runs = listOf(TextRun(0, 21, bold = true))
                                ),
                                bounds = DocRect(25.0, 80.0, 110.0, 105.0),
                                transform = AffineTransform2D.IDENTITY,
                                style = ObjectStyle(zOrder = 10L)
                            )
                        )
                    )
                )
            )
            runtime.drainEngineLoopForTest()

            // 2. Acceptance22: Select strokes with freehand lasso and change color + thickness
            val lassoPolygon = listOf(
                DocPoint(10.0, 15.0),
                DocPoint(135.0, 15.0),
                DocPoint(135.0, 75.0),
                DocPoint(10.0, 75.0)
            )
            val selOut = runtime.selectObjects(
                session = session,
                query = SelectionQuery(
                    mode = SelectionMode.FREEHAND,
                    rule = SelectionRule.CONTAINS,
                    combinator = SelectionCombinator.REPLACE,
                    polygonPoints = lassoPolygon
                )
            )
            assertTrue(selOut.isSuccess, "Lasso selection must succeed")
            val selectedIds = selOut.getOrNull()!!.frozenSelectedIds
            assertEquals(setOf(stroke1Id, stroke2Id), selectedIds)

            val newColor = 0xFFDC2626.toInt()
            val newWidth = 5.0
            val styleRes = runtime.submitObjectAction(
                session = session,
                action = ObjectAction.ApplyStyle(
                    targets = selectedIds,
                    patch = StylePatch(
                        colorArgb = newColor,
                        strokeWidthPt = newWidth
                    )
                )
            )
            assertTrue(styleRes.isSuccess)
            runtime.drainEngineLoopForTest()

            val snapStyled = runtime.latestSnapshot(session)!!
            val s1Styled = snapStyled.getObject(stroke1Id)!!
            val s2Styled = snapStyled.getObject(stroke2Id)!!
            val s1Payload = s1Styled.payload as StrokePayload
            val s2Payload = s2Styled.payload as StrokePayload
            assertEquals(newColor, s1Payload.color)
            assertEquals(newWidth, s1Payload.baseWidthPt, 1e-6)
            assertEquals(newColor, s2Payload.color)
            assertEquals(newWidth, s2Payload.baseWidthPt, 1e-6)

            // Verify mixed selection (strokes + Textbox) keeps Textbox as a separate TextPayload object
            val mixedSel = runtime.selectObjects(
                session = session,
                query = SelectionQuery(
                    mode = SelectionMode.RECTANGLE,
                    rule = SelectionRule.INTERSECTS,
                    combinator = SelectionCombinator.REPLACE,
                    rect = DocRect(10.0, 10.0, 140.0, 115.0),
                    filter = SelectionFilter(allowedKinds = setOf(ObjectKindFilter.STROKE, ObjectKindFilter.TEXT))
                )
            ).getOrNull()!!
            assertEquals(setOf(stroke1Id, stroke2Id, expectedTextId), mixedSel.frozenSelectedIds)

            // 3. Acceptance24: Move selected region to another part of the page (+30, +40)
            val beforeMoveBounds1 = s1Styled.bounds
            val moveRes = runtime.submitObjectAction(
                session = session,
                action = ObjectAction.Move(
                    targets = setOf(stroke1Id, stroke2Id),
                    deltaX = 30.0,
                    deltaY = 40.0
                )
            )
            assertTrue(moveRes.isSuccess)
            runtime.drainEngineLoopForTest()

            val snapMoved = runtime.latestSnapshot(session)!!
            val s1Moved = snapMoved.getObject(stroke1Id)!!
            assertEquals(beforeMoveBounds1.left + 30.0, s1Moved.bounds.left, 1e-5)
            assertEquals(beforeMoveBounds1.top + 40.0, s1Moved.bounds.top, 1e-5)

            // Also test Flip + Undo to verify reversible transforms
            val flipRes = runtime.submitObjectAction(
                session = session,
                action = ObjectAction.Flip(
                    targets = setOf(stroke1Id, stroke2Id),
                    axis = FlipAxis.HORIZONTAL
                )
            )
            assertTrue(flipRes.isSuccess)
            runtime.drainEngineLoopForTest()
            runtime.trySend(AppCommand.Undo(session))
            runtime.drainEngineLoopForTest()
            assertEquals(
                s1Moved.transform,
                runtime.latestSnapshot(session)!!.getObject(stroke1Id)!!.transform
            )

            // 4. Acceptance25: Group and Ungroup selected objects
            val groupId = ObjectId("group-f07-main")
            val groupRes = runtime.submitObjectAction(
                session = session,
                action = ObjectAction.Group(
                    targets = setOf(stroke1Id, stroke2Id),
                    newGroupId = groupId
                )
            )
            assertTrue(groupRes.isSuccess)
            runtime.drainEngineLoopForTest()

            val snapGrouped = runtime.latestSnapshot(session)!!
            val groupObj = snapGrouped.getObject(groupId)
            assertNotNull(groupObj, "Group object must exist after Group action")
            assertEquals(setOf(stroke1Id, stroke2Id), (groupObj!!.payload as GroupPayload).members.toSet())

            // Moving the group moves each child member exactly once (no double transform)
            val preGroupMoveBounds = snapGrouped.getObject(stroke1Id)!!.bounds
            runtime.submitObjectAction(
                session = session,
                action = ObjectAction.Move(
                    targets = setOf(groupId),
                    deltaX = 10.0,
                    deltaY = 5.0
                )
            )
            runtime.drainEngineLoopForTest()
            val postGroupMoveBounds = runtime.latestSnapshot(session)!!.getObject(stroke1Id)!!.bounds
            assertEquals(preGroupMoveBounds.left + 10.0, postGroupMoveBounds.left, 1e-5)
            assertEquals(preGroupMoveBounds.top + 5.0, postGroupMoveBounds.top, 1e-5)

            // Ungroup and verify group container is removed while children remain intact
            val ungroupRes = runtime.submitObjectAction(
                session = session,
                action = ObjectAction.Ungroup(groupIds = setOf(groupId))
            )
            assertTrue(ungroupRes.isSuccess)
            runtime.drainEngineLoopForTest()
            val snapUngrouped = runtime.latestSnapshot(session)!!
            assertEquals(null, snapUngrouped.getObject(groupId))
            assertNotNull(snapUngrouped.getObject(stroke1Id))

            // 5. Acceptance23: Segment eraser splits stroke 1 across the middle while keeping vector sub-strokes
            // Stroke 1 is currently at x in [60, 160], y around 75.0 (30 + 40 + 5 = 75)
            val s1Current = snapUngrouped.getObject(stroke1Id)!!
            val midX = (s1Current.bounds.left + s1Current.bounds.right) * 0.5
            val eraseRes = runtime.submitObjectAction(
                session = session,
                action = ObjectAction.Erase(
                    spec = EraserSpec(
                        mode = EraserMode.SEGMENT,
                        shape = EraserShape.CIRCLE,
                        radiusPt = 8.0,
                        layerPolicy = EraserLayerPolicy.ACTIVE_LAYER_ONLY
                    ),
                    pathPoints = listOf(
                        DocPoint(midX, s1Current.bounds.top - 10.0),
                        DocPoint(midX, s1Current.bounds.bottom + 10.0)
                    )
                )
            )
            assertTrue(eraseRes.isSuccess)
            runtime.drainEngineLoopForTest()

            val snapErased = runtime.latestSnapshot(session)!!
            assertEquals(null, snapErased.getObject(stroke1Id), "Original split stroke must be replaced")
            val remainingStrokes = snapErased.orderedSceneObjects().filter { it.payload is StrokePayload }
            assertEquals(3, remainingStrokes.size, "Stroke 1 split into 2 vector fragments + Stroke 2 untouched = 3 strokes")
            // Textbox must remain untouched by segment eraser
            assertNotNull(snapErased.getObject(expectedTextId), "Textbox must not be erased by stroke eraser")

            // 6. Copy & Paste one of the remaining strokes via clipboard
            val copiedTarget = remainingStrokes.first().id
            val bundle = runtime.copySelectionToClipboard(
                session = session,
                targets = setOf(copiedTarget)
            ).getOrNull()!!
            assertEquals(1, bundle.entries.size)

            val pasteRes = runtime.submitObjectAction(
                session = session,
                action = ObjectAction.Paste(
                    bundle = bundle,
                    targetCenter = DocPoint(100.0, 100.0),
                    idPrefix = "paste-1"
                )
            )
            assertTrue(pasteRes.isSuccess)
            runtime.drainEngineLoopForTest()

            val snapPasted = runtime.latestSnapshot(session)!!
            val strokesAfterPaste = snapPasted.orderedSceneObjects().filter { it.payload is StrokePayload }
            assertEquals(4, strokesAfterPaste.size, "Pasted stroke must increase vector stroke count to 4")
            expectedStrokeCountAfterEdits = strokesAfterPaste.size

            assertEquals(SaveStatusKind.SAVED, runtime.latestSaveStatus(session)?.status)
        } finally {
            renderer.close()
            runtime.closeForTest()
        }

        // 7. Reopen from SQLiteDocumentStore and verify vector reload + 4x zoom render without pixelation
        val runtime2 = Runtime.start(config).getOrNull()!!
        val renderer2 = Renderer(
            geometryPort = runtime2.defaultGeometryPort,
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
        try {
            val reopenedSession = runtime2.openSessionSyncForTest(docId)
            val reopenedSnap = runtime2.latestSnapshot(reopenedSession)!!
            val reopenedStrokes = reopenedSnap.orderedSceneObjects().filter { it.payload is StrokePayload }
            assertEquals(expectedStrokeCountAfterEdits, reopenedStrokes.size)
            assertNotNull(reopenedSnap.getObject(expectedTextId))

            for (strokeObj in reopenedStrokes) {
                val payload = strokeObj.payload as StrokePayload
                assertTrue(payload.samples.size >= 2, "Reloaded stroke ${strokeObj.id.value} must have vector samples")
                assertEquals(0xFFDC2626.toInt(), payload.color)
            }

            // Render at 4x zoom centered around stroke 1 fragments
            val frameReply = renderer2.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = reopenedSnap,
                        camera = CameraTransform(scale = 4.0, translateX = -200.0, translateY = -220.0),
                        viewportWidthPx = 256,
                        viewportHeightPx = 256
                    ),
                    capturePixels = true
                )
            ).getOrNull() as RenderReply.FrameDrawn
            val pixels = frameReply.copyCapturedPixels()!!
            val redInkPixels = pixels.count { px ->
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                r > 180 && g < 80 && b < 80
            }
            assertTrue(redInkPixels > 30, "4x zoom render must rasterize vector strokes cleanly (redInkPixels=$redInkPixels)")
        } finally {
            renderer2.close()
            runtime2.closeForTest()
        }
    }
}
