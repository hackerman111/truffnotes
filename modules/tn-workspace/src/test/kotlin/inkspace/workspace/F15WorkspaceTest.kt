package inkspace.workspace

import inkspace.core.workspace.*
import inkspace.core.unit.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class F15WorkspaceTest {
    @Test fun zoomCoordinatesMatchMainViewAndAdvanceHasNoGap() {
        val zoom = ZoomViewState(region = DocRect(20.0, 30.0, 220.0, 90.0), overlapPt = 20.0)
        val camera = zoom.camera(1000, 300)
        val point = camera.viewToDoc(ViewPoint(250.0, 100.0))
        assertEquals(70.0, point.x, .01)
        assertEquals(50.0, point.y, .01)
        val next = zoom.advance(600.0)
        assertEquals(200.0, next.region.left)
        assertEquals(zoom.region.right - zoom.overlapPt, next.region.left)
        val line = zoom.copy(region = DocRect(450.0, 30.0, 650.0, 90.0)).advance(600.0)
        assertEquals(zoom.lineStartPt, line.region.left)
        assertEquals(zoom.region.top + zoom.lineHeightPt, line.region.top)
    }
    @Test fun toolbar_orientation_and_palette_isolation() {
        val ws = Workspace()
        ws.dispatch(ViewAction.Open("a"))
        ws.dispatch(ViewAction.SetToolConfig("pen", ToolConfig(color = 0xff123456.toInt(), widthPt = 4.0)))
        ws.dispatch(ViewAction.SetToolbarLayout(ToolbarLayout(tools = listOf("eraser", "pen"), dockBottom = true), true))
        val settings = ws.state.active!!.settings
        assertEquals(4.0, settings.configFor("pen").widthPt)
        assertEquals(2.0, settings.configFor("highlighter").widthPt)
        assertTrue("lasso" in settings.landscapeToolbar.visibleTools())
        assertFalse(settings.portraitToolbar.dockBottom)
        assertEquals(listOf(0xff000000.toInt(), 0xffd32f2f.toInt(), 0xff1976d2.toInt()), Palette().colors)
        assertFailsWith<IllegalArgumentException> { Palette(List(13) { it }) }
    }
    @Test fun gesture_reset_and_zoom_identity() {
        val ws = Workspace()
        ws.dispatch(ViewAction.Open("a"))
        val tab = ws.state.active!!
        ws.dispatch(ViewAction.OpenZoom(ZoomViewState()))
        ws.dispatch(ViewAction.SetGestureConfig(inkspace.core.ink.GestureSettings(holdToShapeEnabled = false)))
        assertEquals(tab.documentId, ws.state.active!!.documentId)
        assertEquals(1, ws.state.tabs.size)
        assertNotNull(ws.state.active!!.zoom)
        assertFalse(ws.state.active!!.settings.gestures.holdToShapeEnabled)
        ws.dispatch(ViewAction.SetGestureConfig(inkspace.core.ink.GestureSettings()))
        assertEquals(inkspace.core.ink.GestureSettings(), ws.state.active!!.settings.gestures)
    }
    @Test fun tool_return_preserves_selected_tool_across_zoom_and_orientation() {
        val ws = Workspace()
        ws.dispatch(ViewAction.Open("a"))
        ws.dispatch(ViewAction.Command("tape"))
        ws.dispatch(ViewAction.OpenZoom(ZoomViewState()))
        ws.dispatch(ViewAction.SetToolbarLayout(ToolbarLayout(dockBottom = true), true))
        ws.dispatch(ViewAction.OpenZoom(null))
        assertEquals("tape", ws.state.active!!.tool)
        ws.dispatch(ViewAction.Command("readonly"))
        assertTrue(ws.state.active!!.readOnly)
        assertEquals("tape", ws.state.active!!.tool)
        assertFalse(ws.dispatch(ViewAction.Command("undo")).accepted)
    }
}
