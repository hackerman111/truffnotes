package inkspace.render

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.*
import inkspace.core.render.*
import inkspace.core.unit.*
import inkspace.testsupport.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ShapeReplayRegressionTest {
    private fun render(payload: ShapePayload, transform: AffineTransform2D = AffineTransform2D.IDENTITY): IntArray {
        val state = DocumentState.createInitial(DocumentId("shapes"))
        val local = DocRect(10.0,10.0,50.0,30.0)
        state.putResidentObjectDirect(SceneObject(ObjectId("shape"),SpaceId("space-1"),LayerId("layer-1"),transform.transformRect(local),payload,transform=transform))
        return Renderer(FakeGeometryPort(),FakeTextPort(),FakeResourcePort()).use { renderer ->
            (renderer.renderOnExecutor(RenderRequest.Draw(Scene(state.snapshot(),viewportWidthPx=80,viewportHeightPx=80),true)).getOrNull()!! as RenderReply.FrameDrawn).copyCapturedPixels()!!
        }
    }
    @Test fun line_has_no_rectangle_border() {
        val p = render(ShapePayload(ShapeType.Line,-16777216,vertices=listOf(DocPoint(10.0,10.0),DocPoint(50.0,30.0))))
        assertEquals(-1,p[10*80+30]); assertEquals(-16777216,p[20*80+30])
    }
    @Test fun ellipse_without_fill_has_transparent_center_and_full_width() {
        val p = render(ShapePayload(ShapeType.Ellipse,-16777216,strokeWidthPt=2.0))
        assertEquals(-1,p[20*80+30]); assertEquals(-16777216,p[20*80+49])
    }
    @Test fun triangle_fill_respects_vertices_instead_of_bounding_box() {
        val p = render(ShapePayload(ShapeType.Triangle,-16777216,fillColor=-65536,vertices=listOf(DocPoint(30.0,10.0),DocPoint(50.0,30.0),DocPoint(10.0,30.0))))
        assertEquals(-1,p[12*80+12]); assertEquals(-65536,p[24*80+30])
    }
}
