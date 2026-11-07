package inkspace.app

import inkspace.core.id.DocumentId
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.workspace.LibraryAction
import inkspace.core.workspace.ViewAction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class EditorInputRegressionTest {
    @Test fun inputCommitsVisibleStrokeThroughApplicationContainer(@TempDir dir: Path) {
        val container = TruffnotesAppContainer.create(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            container.openOrRestoreSession(DocumentId("input"))
            await { container.syncEvents(); container.resolveActiveSession() != null }
            val session = container.resolveActiveSession()!!
            for ((i, action) in listOf(InputActionKind.DOWN, InputActionKind.MOVE, InputActionKind.UP).withIndex()) {
                assertTrue(container.submitRawMotionBatch(0, action, 1, floatArrayOf(10f + i * 30),
                    floatArrayOf(20f + i * 20), floatArrayOf(.5f), floatArrayOf(0f), longArrayOf(100L + i * 20)).isSuccess)
            }
            await { container.syncEvents(); container.runtime.latestSnapshot(session)!!.totalObjectCount == 1 }
            assertEquals(1, container.runtime.sceneForRendering(session)!!.snapshot.totalObjectCount)
        } finally { container.closeAsync() }
    }
    @Test fun activeWorkspaceSessionWinsWhileControllerStillHasPreviousSession(@TempDir dir: Path) {
        val container = TruffnotesAppContainer.create(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            container.openOrRestoreSession(DocumentId("old"))
            await { container.syncEvents(); container.resolveActiveSession()?.document == DocumentId("old") }
            assertTrue(container.notebooks.submit(LibraryAction.Create("new", "New")).isSuccess)
            assertTrue(container.notebooks.dispatch(ViewAction.Open("new")).accepted)
            await { container.runtime.activeSessionFor("new") != null }
            assertEquals(DocumentId("new"), container.resolveActiveSession()?.document)
        } finally { container.closeAsync() }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), "Expected editor state was never reached")
    }
}
