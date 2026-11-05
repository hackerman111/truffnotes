package inkspace.render

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.PageDescriptor
import inkspace.core.mutation.AddLayer
import inkspace.core.mutation.AddPage
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.SetLayerStyle
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.render.DraftStrokeSceneItem
import inkspace.core.render.RenderReply
import inkspace.core.render.RenderRequest
import inkspace.core.render.Scene
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.testsupport.FakeGeometryPort
import inkspace.testsupport.FakeResourcePort
import inkspace.testsupport.FakeTextPort
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class F05SceneAndGoldenTest {

    private lateinit var renderer: Renderer

    @BeforeEach
    fun setUp() {
        renderer = Renderer(
            geometryPort = FakeGeometryPort(),
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
    }

    @AfterEach
    fun tearDown() {
        renderer.close()
    }

    private fun createStateWithPage(
        docId: DocumentId = DocumentId("doc-f05-1"),
        pageRect: DocRect = DocRect(0.0, 0.0, 200.0, 200.0)
    ): DocumentState {
        return DocumentState.createInitial(
            documentId = docId,
            initialPage = PageDescriptor(
                id = PageId("page-1"),
                spaceId = SpaceId("space-1"),
                widthPt = pageRect.width,
                heightPt = pageRect.height
            )
        )
    }

    @Test
    fun camera_transforms_and_page_clipping() {
        val docId = DocumentId("doc-cam-clip")
        val state = createStateWithPage(docId, DocRect(0.0, 0.0, 100.0, 100.0))

        // Insert a filled rectangle extending from (20, 20) to (140, 60) -> right of x=100 is outside page bounds
        val insertPlan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("rect-clip"),
                    payload = ShapePayload(
                        shapeType = ShapeType.Rectangle,
                        strokeColor = 0xFFFF0000.toInt(),
                        fillColor = 0xFFFF0000.toInt(),
                        strokeWidthPt = 1.0
                    ),
                    bounds = DocRect(20.0, 20.0, 140.0, 60.0)
                )
            ),
            gesture = GestureId("g-rect"),
            label = UndoLabel("rect")
        )
        applyPlan(state, validatePlan(state.snapshot(), insertPlan, Limits()).getOrNull()!!, Limits())

        // Camera: scale=1.0, translate=(10.0, 5.0)
        // Inside page & rect: doc (50, 40) -> view (60, 45) => RED
        // Outside page (clipped): doc (120, 40) -> view (130, 45) => BACKGROUND WHITE
        val scene = Scene(
            snapshot = state.snapshot(),
            camera = CameraTransform(scale = 1.0, translateX = 10.0, translateY = 5.0),
            viewportWidthPx = 200,
            viewportHeightPx = 200,
            backgroundColorArgb = 0xFFFFFFFF.toInt(),
            clipToPageBounds = true
        )

        val reply = renderer.renderOnExecutor(
            RenderRequest.Draw(scene = scene, capturePixels = true)
        ).getOrNull()!! as RenderReply.FrameDrawn

        assertEquals(0xFFFF0000.toInt(), reply.pixelAt(60, 45))
        assertEquals(0xFFFFFFFF.toInt(), reply.pixelAt(130, 45))
    }

    @Test
    fun z_order_and_hidden_layers() {
        val docId = DocumentId("doc-z-order")
        val state = createStateWithPage(docId, DocRect(0.0, 0.0, 100.0, 100.0))

        // Add layer-2 above layer-1, and layer-hidden (invisible) above layer-2
        val setupPlan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            mutations = listOf(
                AddLayer(LayerDescriptor(LayerId("layer-2"), PageId("page-1"), "Layer 2", opacity = 1.0f, isVisible = true)),
                AddLayer(LayerDescriptor(LayerId("layer-hidden"), PageId("page-1"), "Hidden", opacity = 1.0f, isVisible = false)),
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("bottom-red"),
                    payload = ShapePayload(
                        shapeType = ShapeType.Rectangle,
                        strokeColor = 0xFFFF0000.toInt(),
                        fillColor = 0xFFFF0000.toInt(),
                        strokeWidthPt = 1.0
                    ),
                    bounds = DocRect(10.0, 10.0, 70.0, 70.0)
                ),
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-2"),
                    id = ObjectId("top-blue"),
                    payload = ShapePayload(
                        shapeType = ShapeType.Rectangle,
                        strokeColor = 0xFF0000FF.toInt(),
                        fillColor = 0xFF0000FF.toInt(),
                        strokeWidthPt = 1.0
                    ),
                    bounds = DocRect(30.0, 30.0, 80.0, 80.0)
                ),
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-hidden"),
                    id = ObjectId("hidden-green"),
                    payload = ShapePayload(
                        shapeType = ShapeType.Rectangle,
                        strokeColor = 0xFF00FF00.toInt(),
                        fillColor = 0xFF00FF00.toInt(),
                        strokeWidthPt = 1.0
                    ),
                    bounds = DocRect(10.0, 10.0, 80.0, 80.0)
                )
            ),
            gesture = GestureId("g-layers"),
            label = UndoLabel("layers")
        )
        applyPlan(state, validatePlan(state.snapshot(), setupPlan, Limits()).getOrNull()!!, Limits())

        val scene = Scene(
            snapshot = state.snapshot(),
            viewportWidthPx = 100,
            viewportHeightPx = 100
        )
        val reply = renderer.renderOnExecutor(
            RenderRequest.Draw(scene = scene, capturePixels = true)
        ).getOrNull()!! as RenderReply.FrameDrawn

        // (20, 20) is only in bottom-red -> RED (hidden-green is ignored)
        assertEquals(0xFFFF0000.toInt(), reply.pixelAt(20, 20))
        // (50, 50) is in overlap of bottom-red and top-blue -> BLUE wins by z-order
        assertEquals(0xFF0000FF.toInt(), reply.pixelAt(50, 50))
    }

    @Test
    fun highlighter_compositing_no_self_darkening_and_layer_opacity() {
        val docId = DocumentId("doc-highlighter")
        val state = createStateWithPage(docId, DocRect(0.0, 0.0, 100.0, 100.0))

        // Self-crossing highlighter stroke: horizontal segment (10, 50) -> (90, 50) then vertical (50, 10) -> (50, 90)
        val highlighterColor = 0x80FFEB3B.toInt()
        val hlPlan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("hl-cross"),
                    payload = StrokePayload(
                        brushId = "highlighter",
                        color = highlighterColor,
                        baseWidthPt = 12.0,
                        origin = DocPoint(0.0, 0.0),
                        samples = listOf(
                            StrokeSamplePoint(10f, 50f, 0.8f),
                            StrokeSamplePoint(90f, 50f, 0.8f),
                            StrokeSamplePoint(50f, 50f, 0.8f),
                            StrokeSamplePoint(50f, 10f, 0.8f),
                            StrokeSamplePoint(50f, 90f, 0.8f)
                        )
                    ),
                    bounds = DocRect(4.0, 4.0, 96.0, 96.0)
                )
            ),
            gesture = GestureId("g-hl"),
            label = UndoLabel("hl")
        )
        applyPlan(state, validatePlan(state.snapshot(), hlPlan, Limits()).getOrNull()!!, Limits())

        val scene = Scene(
            snapshot = state.snapshot(),
            viewportWidthPx = 100,
            viewportHeightPx = 100,
            backgroundColorArgb = 0xFFFFFFFF.toInt()
        )
        val screenReply = renderer.renderOnExecutor(
            RenderRequest.Draw(scene = scene, capturePixels = true)
        ).getOrNull()!! as RenderReply.FrameDrawn

        val nonOverlapPixel = screenReply.pixelAt(25, 50)
        val selfOverlapCenterPixel = screenReply.pixelAt(50, 50)
        assertNotEquals(0xFFFFFFFF.toInt(), nonOverlapPixel)
        // Self-overlapping highlighter stroke must NOT double-darken at its intersection (50, 50)
        assertEquals(nonOverlapPixel, selfOverlapCenterPixel)

        // Verify PdfOverlay produces identical compositing as screen Draw
        val pdfReply = renderer.renderOnExecutor(
            RenderRequest.PdfOverlay(
                scene = scene,
                pageId = PageId("page-1"),
                pageBoundsDoc = DocRect(0.0, 0.0, 100.0, 100.0)
            )
        ).getOrNull()!! as RenderReply.PdfOverlayRendered

        assertTrue(pdfReply.pdfByteSize() > 0)
        val cmp = GoldenImageComparator.compare(
            expectedArgb = screenReply.copyCapturedPixels()!!,
            actualArgb = pdfReply.copyPreviewPixels(),
            widthPx = 100,
            heightPx = 100
        )
        assertTrue(cmp.matches, "Screen and PDF overlay highlighter compositing must match: $cmp")

        // Now set layer-1 opacity to 0.5f and verify pixel blends closer to white background
        val opacityPlan = EditPlan(
            base = DocVersion(docId, Revision(1L)),
            mutations = listOf(
                SetLayerStyle(layerId = LayerId("layer-1"), opacity = 0.5f, isVisible = true)
            ),
            gesture = GestureId("g-opacity"),
            label = UndoLabel("opacity")
        )
        applyPlan(state, validatePlan(state.snapshot(), opacityPlan, Limits()).getOrNull()!!, Limits())

        val halfOpacityReply = renderer.renderOnExecutor(
            RenderRequest.Draw(scene = scene.copy(snapshot = state.snapshot()), capturePixels = true)
        ).getOrNull()!! as RenderReply.FrameDrawn
        val halfOpacityPixel = halfOpacityReply.pixelAt(25, 50)
        assertNotEquals(nonOverlapPixel, halfOpacityPixel)
        assertNotEquals(0xFFFFFFFF.toInt(), halfOpacityPixel)
    }

    @Test
    fun camera_relative_double_to_float_precision_at_large_world_offset() {
        val docNear = DocumentId("doc-near")
        val stateNear = DocumentState.createInitial(docNear)
        val nearPlan = EditPlan(
            base = DocVersion(docNear, Revision(0L)),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("stroke-near"),
                    payload = StrokePayload(
                        brushId = "ball",
                        color = 0xFF112233.toInt(),
                        baseWidthPt = 4.0,
                        origin = DocPoint(0.0, 0.0),
                        samples = listOf(
                            StrokeSamplePoint(10.25f, 15.75f, 0.5f),
                            StrokeSamplePoint(40.50f, 45.25f, 0.5f)
                        )
                    ),
                    bounds = DocRect(0.0, 0.0, 60.0, 60.0)
                )
            ),
            gesture = GestureId("g-near"),
            label = UndoLabel("near")
        )
        applyPlan(stateNear, validatePlan(stateNear.snapshot(), nearPlan, Limits()).getOrNull()!!, Limits())

        val docFar = DocumentId("doc-far")
        val stateFar = DocumentState.createInitial(docFar)
        val farOffset = 10_000_000.0 // 1e7 pt — would lose sub-point precision if cast to Float before camera transform
        val farPlan = EditPlan(
            base = DocVersion(docFar, Revision(0L)),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("stroke-far"),
                    payload = StrokePayload(
                        brushId = "ball",
                        color = 0xFF112233.toInt(),
                        baseWidthPt = 4.0,
                        origin = DocPoint(farOffset, farOffset),
                        samples = listOf(
                            StrokeSamplePoint(10.25f, 15.75f, 0.5f),
                            StrokeSamplePoint(40.50f, 45.25f, 0.5f)
                        )
                    ),
                    bounds = DocRect(farOffset, farOffset, farOffset + 60.0, farOffset + 60.0)
                )
            ),
            gesture = GestureId("g-far"),
            label = UndoLabel("far")
        )
        applyPlan(stateFar, validatePlan(stateFar.snapshot(), farPlan, Limits()).getOrNull()!!, Limits())

        val nearFrame = renderer.renderOnExecutor(
            RenderRequest.Draw(
                scene = Scene(
                    snapshot = stateNear.snapshot(),
                    camera = CameraTransform(scale = 2.0, translateX = 5.0, translateY = 7.0),
                    viewportWidthPx = 120,
                    viewportHeightPx = 120,
                    clipToPageBounds = false
                ),
                capturePixels = true
            )
        ).getOrNull()!! as RenderReply.FrameDrawn

        val farFrame = renderer.renderOnExecutor(
            RenderRequest.Draw(
                scene = Scene(
                    snapshot = stateFar.snapshot(),
                    camera = CameraTransform(
                        scale = 2.0,
                        translateX = -farOffset * 2.0 + 5.0,
                        translateY = -farOffset * 2.0 + 7.0
                    ),
                    viewportWidthPx = 120,
                    viewportHeightPx = 120,
                    clipToPageBounds = false
                ),
                capturePixels = true
            )
        ).getOrNull()!! as RenderReply.FrameDrawn

        val cmp = GoldenImageComparator.compare(
            expectedArgb = nearFrame.copyCapturedPixels()!!,
            actualArgb = farFrame.copyCapturedPixels()!!,
            widthPx = 120,
            heightPx = 120
        )
        assertTrue(cmp.matches && cmp.mismatchedPixelsCount == 0, "Expected 0 pixel drift at 1e7 pt offset: $cmp")
    }

    @Test
    fun draft_to_committed_pixel_consistency() {
        val docId = DocumentId("doc-draft-commit")
        val state = createStateWithPage(docId, DocRect(0.0, 0.0, 120.0, 120.0))

        val strokePayload = StrokePayload(
            brushId = "ball",
            color = 0xFF0D47A1.toInt(),
            baseWidthPt = 6.0,
            origin = DocPoint(15.0, 20.0),
            samples = listOf(
                StrokeSamplePoint(0f, 0f, 0.5f),
                StrokeSamplePoint(30f, 25f, 0.7f),
                StrokeSamplePoint(65f, 50f, 0.8f)
            )
        )
        val strokeBounds = DocRect(10.0, 15.0, 90.0, 80.0)

        // 1. Render as transient DraftStrokeSceneItem before commit
        val draftScene = Scene(
            snapshot = state.snapshot(),
            viewportWidthPx = 120,
            viewportHeightPx = 120,
            draftStrokes = listOf(
                DraftStrokeSceneItem(
                    draftId = ObjectId("stroke-1"),
                    spaceId = SpaceId("space-1"),
                    layerId = LayerId("layer-1"),
                    bounds = strokeBounds,
                    payload = strokePayload
                )
            )
        )
        val draftReply = renderer.renderOnExecutor(
            RenderRequest.Draw(scene = draftScene, capturePixels = true)
        ).getOrNull()!! as RenderReply.FrameDrawn

        // 2. Commit the exact same stroke into DocumentState and render without draftStrokes
        val commitPlan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("stroke-1"),
                    payload = strokePayload,
                    bounds = strokeBounds
                )
            ),
            gesture = GestureId("g-commit-1"),
            label = UndoLabel("commit-1")
        )
        applyPlan(state, validatePlan(state.snapshot(), commitPlan, Limits()).getOrNull()!!, Limits())

        val committedScene = Scene(
            snapshot = state.snapshot(),
            viewportWidthPx = 120,
            viewportHeightPx = 120,
            draftStrokes = emptyList()
        )
        val committedReply = renderer.renderOnExecutor(
            RenderRequest.Draw(scene = committedScene, capturePixels = true)
        ).getOrNull()!! as RenderReply.FrameDrawn

        val cmp = GoldenImageComparator.compare(
            expectedArgb = draftReply.copyCapturedPixels()!!,
            actualArgb = committedReply.copyCapturedPixels()!!,
            widthPx = 120,
            heightPx = 120
        )
        assertTrue(cmp.matches && cmp.mismatchedPixelsCount == 0, "Draft and committed stroke pixels must be identical: $cmp")
    }
}
