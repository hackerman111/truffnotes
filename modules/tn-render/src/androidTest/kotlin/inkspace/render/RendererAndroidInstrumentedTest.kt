package inkspace.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkspace.core.error.RenderError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentState
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.render.RenderReply
import inkspace.core.render.RenderRequest
import inkspace.core.render.Scene
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.testsupport.FakeGeometryPort
import inkspace.testsupport.FakeResourcePort
import inkspace.testsupport.FakeTextPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RendererAndroidInstrumentedTest {

    @Test
    fun rendererDrawsToAndroidBitmapCanvasAndGeneratesSnapshotAndPdfOverlay() {
        val renderer = Renderer(
            geometryPort = FakeGeometryPort(),
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val docId = DocumentId("doc-android-render")
            val state = DocumentState.createInitial(docId)
            val insert = InsertObject(
                space = SpaceId("space-1"),
                layer = LayerId("layer-1"),
                id = ObjectId("stroke-1"),
                payload = StrokePayload(
                    brushId = "ball",
                    color = 0xFFFF0000.toInt(),
                    baseWidthPt = 4.0,
                    origin = DocPoint(20.0, 20.0),
                    samples = listOf(
                        StrokeSamplePoint(relX = 0f, relY = 0f, pressure = 0.5f),
                        StrokeSamplePoint(relX = 60f, relY = 60f, pressure = 0.7f)
                    )
                ),
                bounds = DocRect(16.0, 16.0, 84.0, 84.0)
            )
            val plan = EditPlan(
                base = DocVersion(docId, Revision(0L)),
                mutations = listOf(insert),
                gesture = GestureId("g1"),
                label = UndoLabel("u1")
            )
            applyPlan(state, validatePlan(state.snapshot(), plan, Limits()).getOrNull()!!, Limits())

            val scene = Scene(
                snapshot = state.snapshot(),
                viewportWidthPx = 128,
                viewportHeightPx = 128,
                surfaceEpoch = SurfaceEpoch(1L)
            )

            val drawOutcome = renderer.renderOnExecutor(
                request = RenderRequest.Draw(scene = scene, capturePixels = true),
                androidCanvas = canvas
            )
            assertTrue(drawOutcome.isSuccess)
            val drawReply = drawOutcome.getOrNull() as RenderReply.FrameDrawn
            assertEquals(1, drawReply.stats.drawnStrokesCount)

            val snapOutcome = renderer.renderOnExecutor(
                request = RenderRequest.Snapshot(
                    scene = scene,
                    regionDoc = DocRect(0.0, 0.0, 128.0, 128.0),
                    scale = 1.0
                )
            )
            assertTrue(snapOutcome.isSuccess)
            val snapReply = snapOutcome.getOrNull() as RenderReply.SnapshotRendered
            assertEquals(128, snapReply.widthPx)

            val pdfOutcome = renderer.renderOnExecutor(
                request = RenderRequest.PdfOverlay(
                    scene = scene,
                    pageId = PageId("page-1"),
                    pageBoundsDoc = DocRect(0.0, 0.0, 128.0, 128.0)
                )
            )
            assertTrue(pdfOutcome.isSuccess)
            val pdfReply = pdfOutcome.getOrNull() as RenderReply.PdfOverlayRendered
            assertTrue(pdfReply.pdfByteSize() > 32)

            // Surface release acknowledgement
            assertTrue(renderer.releaseSurface(SurfaceEpoch(1L)))
            val staleRes = renderer.renderOnExecutor(
                request = RenderRequest.Draw(scene = scene, capturePixels = false)
            )
            assertTrue(staleRes.isFailure)
            assertTrue(staleRes.errorOrNull() is RenderError.InvalidSurface)
        } finally {
            bitmap.recycle()
            renderer.close()
        }
    }
}
