package inkspace.workspace

import inkspace.core.workspace.*
import org.junit.jupiter.api.Test
import inkspace.core.unit.CameraTransform
import kotlin.test.*

class WorkspaceTest {
    @Test fun dirtyTabCannotCloseUntilDurableAndColdTabsHoldOnlyMetadata() {
        val workspace = Workspace()
        repeat(100) { workspace.dispatch(ViewAction.Open("doc-$it")) }
        assertEquals(100, workspace.state.tabs.size)
        workspace.dispatch(ViewAction.Dirty(true))
        assertFalse(workspace.dispatch(ViewAction.Close(workspace.state.activeTab!!)).accepted)
        workspace.dispatch(ViewAction.Dirty(false))
        assertTrue(workspace.dispatch(ViewAction.Close(workspace.state.activeTab!!)).accepted)
        assertTrue(workspace.dispatch(ViewAction.Reopen).accepted)
        assertEquals(100, workspace.state.tabs.size)
    }

    @Test fun registrySharesToolbarAndKeysAndReadOnlyDeniesMutations() {
        assertEquals(CommandRegistry.toolbar("undo"), CommandRegistry.keyboard("z", ctrl = true))
        assertNull(CommandRegistry.keyboard("z", ctrl = true, textFocused = true))
        val workspace = Workspace()
        workspace.dispatch(ViewAction.Open("doc"))
        workspace.dispatch(ViewAction.Camera(CameraTransform(scale = 2.0)))
        val camera = workspace.state.active!!.camera
        workspace.dispatch(ViewAction.Command("focus"))
        assertEquals(camera, workspace.state.active!!.camera)
        workspace.dispatch(ViewAction.Command("readonly"))
        assertFalse(workspace.dispatch(ViewAction.Command("undo")).accepted)
        assertTrue(workspace.dispatch(ViewAction.Command("focus")).accepted)
    }

    @Test fun tabLimitIsEnforcedBeforeMutation() {
        val initial = WorkspaceState((0 until 1000).map { TabState("tab-$it", "doc-$it") }, "tab-0")
        val workspace = Workspace(initial)
        assertFalse(workspace.dispatch(ViewAction.Open("overflow")).accepted)
        assertEquals(initial, workspace.state)
        assertTrue(workspace.dispatch(ViewAction.Open("doc-2")).accepted)
        assertEquals(1000, workspace.state.tabs.size)
    }

    @Test fun closedTabsAndViewMetadataSurviveWorkspaceReconstruction() {
        val workspace = Workspace()
        workspace.dispatch(ViewAction.Open("notes"))
        workspace.dispatch(ViewAction.ActivePage("page-2"))
        workspace.dispatch(ViewAction.ActiveLayer("layer-2"))
        workspace.dispatch(ViewAction.Selection(setOf("object-1")))
        workspace.dispatch(ViewAction.Settings(EditorSettings(textPinned = true, horizontalScroll = true)))
        workspace.dispatch(ViewAction.Close(workspace.state.activeTab!!))
        val restored = Workspace(workspace.state)
        assertTrue(restored.dispatch(ViewAction.Reopen).accepted)
        assertEquals("page-2", restored.state.active!!.activePage)
        assertEquals("layer-2", restored.state.active!!.activeLayer)
        assertEquals(setOf("object-1"), restored.state.active!!.selectedIds)
        assertTrue(restored.state.active!!.settings.textPinned)
    }
}
