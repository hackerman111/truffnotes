package inkspace.render

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.*
import inkspace.core.ports.*
import inkspace.core.outcome.Outcome
import inkspace.core.render.*
import inkspace.core.text.*
import inkspace.core.unit.DocRect
import inkspace.testsupport.FakeGeometryPort
import inkspace.testsupport.FakeResourcePort
import org.junit.jupiter.api.Test
import kotlin.test.*

class F10F11ReplayTest {
    @Test fun exif6_non_square_crop_uses_raw_source_coordinates() {
        val state=DocumentState.createInitial(DocumentId("exif"))
        val asset=AssetId("image")
        state.putResidentObjectDirect(SceneObject(ObjectId("image"),SpaceId("space-1"),LayerId("layer-1"),DocRect(0.0,0.0,20.0,40.0),
            ImagePayload(asset,4,2,cropRect=DocRect(0.0,0.0,2.0,2.0),exifOrientation=6,containerBounds=DocRect(0.0,0.0,20.0,40.0))))
        val resource=object:ResourcePort {
            override fun read(request:ResourceRequest):Outcome<ResourceLease,inkspace.core.error.ResourceError> = Outcome.Success(
                ResourceLease(asset,32,true,imageTile=TileArtifact("i",asset,docBounds=DocRect(0.0,0.0,2.0,4.0),widthPx=2,heightPx=4,argbPixels=IntArray(8){-65536})))
        }
        Renderer(FakeGeometryPort(),inkspace.testsupport.FakeTextPort(),resource).use { renderer ->
            val frame=renderer.renderOnExecutor(RenderRequest.Draw(Scene(state.snapshot(),viewportWidthPx=40,viewportHeightPx=40),true)).getOrNull()!! as RenderReply.FrameDrawn
            val pixels=frame.copyCapturedPixels()!!
            assertEquals(-65536,pixels[5*40+5])
            assertEquals(-1,pixels[35*40+5])
        }
    }
    @Test fun on_canvas_pdf_tile_respects_object_z_order_and_opacity() {
        val state=DocumentState.createInitial(DocumentId("pdf-z"))
        val bounds=DocRect(0.0,0.0,20.0,20.0)
        val shape=SceneObject(ObjectId("shape"),SpaceId("space-1"),LayerId("layer-1"),bounds,
            ShapePayload(ShapeType.Rectangle,-16711936,fillColor=-16711936),style=ObjectStyle(zOrder=0))
        val pdf=shape.copy(id=ObjectId("pdf"),payload=PdfObjectPayload(inkspace.core.pdf.PdfPageRef(AssetId("pdf"),0,bounds)),style=ObjectStyle(zOrder=1))
        state.putResidentObjectDirect(shape);state.putResidentObjectDirect(pdf)
        val tile=TileArtifact("pdf-object:pdf:test",docBounds=bounds,widthPx=2,heightPx=2,argbPixels=IntArray(4) { -65536 })
        Renderer(FakeGeometryPort(),inkspace.testsupport.FakeTextPort(),FakeResourcePort()).use { renderer ->
            fun pixel():Int {
                val frame=renderer.renderOnExecutor(RenderRequest.Draw(Scene(state.snapshot(),viewportWidthPx=40,viewportHeightPx=40,tiles=listOf(tile)),true)).getOrNull()!! as RenderReply.FrameDrawn
                return frame.copyCapturedPixels()!![10*40+10]
            }
            assertEquals(-65536,pixel())
            state.putResidentObjectDirect(pdf.copy(style=ObjectStyle(zOrder=1,opacity=.5f)))
            assertEquals(0xff807f00.toInt(),pixel())
            state.putResidentObjectDirect(pdf.copy(style=ObjectStyle(zOrder=-1)))
            assertEquals(-16711936,pixel())
        }
    }
    @Test fun shared_text_artifact_is_replayed_instead_of_rectangles() {
        val state = DocumentState.createInitial(DocumentId("rich"))
        state.putResidentObjectDirect(SceneObject(ObjectId("text"), SpaceId("space-1"), LayerId("layer-1"), DocRect(0.0,0.0,20.0,20.0), TextPayload("A")))
        var sawPayload = false
        val port = object : TextPort {
            override fun layout(request: TextLayoutRequest): Outcome<TextLayout, inkspace.core.error.TextError> {
                sawPayload = request.payload?.text == "A"
                val artifact = object : TextPaintArtifact {
                    override val estimatedBytes = 100L
                    override fun draw(target: Any) { }
                    override fun offsetAt(x: Float,y: Int) = 0
                    override fun cursorRect(offset: Int) = DocRect(0.0,0.0,1.0,2.0)
                    override fun rasterize(scale: Double,maxPixels: Int) = TextRaster(2,2,intArrayOf(-65536,0,0,-65536))
                }
                return Outcome.Success(TextLayout(2.0,2.0,1,paintPlan=artifact))
            }
        }
        Renderer(FakeGeometryPort(),port,FakeResourcePort()).use { renderer ->
            val frame = renderer.renderOnExecutor(RenderRequest.Draw(Scene(state.snapshot(),viewportWidthPx=40,viewportHeightPx=40),true)).getOrNull()!! as RenderReply.FrameDrawn
            val pixels = frame.copyCapturedPixels()!!
            assertTrue(sawPayload)
            assertEquals(-65536,pixels[0])
            assertEquals(-1,pixels[1])
        }
    }
    @Test fun collapsed_sticky_children_are_culled_without_document_change() {
        val state = DocumentState.createInitial(DocumentId("sticky"))
        val child = SceneObject(ObjectId("child"),SpaceId("space-1"),LayerId("layer-1"),DocRect(0.0,0.0,10.0,10.0),ImagePayload(AssetId("a"),10,10))
        state.putResidentObjectDirect(child)
        state.putResidentObjectDirect(child.copy(id=ObjectId("parent"),payload=StickyPayload(1,"",isCollapsed=true,attachedIds=listOf(child.id))))
        val prepared = ScenePreparer.prepare(Scene(state.snapshot(),viewportWidthPx=40,viewportHeightPx=40))
        assertFalse(prepared.layerGroups.flatMap { it.objects }.any { it.id == child.id })
        assertNotNull(state.getObject(child.id))
    }
}
