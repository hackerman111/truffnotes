package inkspace.runtime

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.TextPayload
import inkspace.core.runtime.*
import inkspace.core.unit.DocRect
import inkspace.pages.PageAction
import inkspace.text.TextAction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class F09F11PlannerIntegrationTest {
    @Test fun pageAndTextUseDurableHistory(@TempDir directory: Path) {
        val config = RuntimeConfig(storageRootPath = directory.toString())
        val runtime = Runtime.start(config).getOrNull()!!
        try {
            val session = runtime.openSessionSyncForTest(DocumentId("feature-integration"))
            val page = PageDescriptor(PageId("new-page"), SpaceId("new-space"), 400.0, 600.0)
            assertTrue(runtime.submitPageAction(session, PageAction.Add(page, LayerDescriptor(LayerId("new-layer"), page.id, "Ink"))).isSuccess)
            runtime.drainEngineLoopForTest()
            assertEquals(2, runtime.latestSnapshot(session)!!.pages.size)
            assertTrue(runtime.submitTextAction(session, TextAction.Create(ObjectId("text"), TextPayload("Привет 🌍"), DocRect(10.0, 10.0, 200.0, 40.0))).isSuccess)
            runtime.drainEngineLoopForTest()
            assertEquals("Привет 🌍", (runtime.latestSnapshot(session)!!.getObjectPayload(ObjectId("text")) as TextPayload).text)
            runtime.trySend(AppCommand.Undo(session))
            runtime.drainEngineLoopForTest()
            assertNull(runtime.latestSnapshot(session)!!.getObject(ObjectId("text")))
            runtime.trySend(AppCommand.Redo(session))
            runtime.drainEngineLoopForTest()
            assertNotNull(runtime.latestSnapshot(session)!!.getObject(ObjectId("text")))
            assertTrue(runtime.submitContentAction(session, inkspace.content.ContentAction.InsertSticky(
                ObjectId("sticky"), 0xFFFFFF00.toInt(), "Remember", DocRect(50.0, 50.0, 150.0, 150.0))).isSuccess)
            runtime.drainEngineLoopForTest()
        } finally { runtime.closeForTest() }
        val reopened = Runtime.start(config).getOrNull()!!
        try {
            val session = reopened.openSessionSyncForTest(DocumentId("feature-integration"))
            assertEquals(2, reopened.latestSnapshot(session)!!.pages.size)
            assertEquals("Remember", (reopened.latestSnapshot(session)!!.getObjectPayload(ObjectId("sticky")) as inkspace.core.payload.StickyPayload).content)
            assertEquals("Привет 🌍", (reopened.latestSnapshot(session)!!.getObjectPayload(ObjectId("text")) as TextPayload).text)
        } finally { reopened.closeForTest() }
    }
}
