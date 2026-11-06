package inkspace.runtime
import inkspace.core.id.*
import inkspace.core.workspace.*
import inkspace.core.runtime.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*
class F09F13NotebookCommandsTest {
 @Test fun pageLayerOutlineAndTextCommandsUseOneReducer(@TempDir dir:Path) {
  val rt=Runtime.start(RuntimeConfig(storageRootPath=dir.toString())).getOrNull()!!
  try {
   val s=rt.openSessionSyncForTest(DocumentId("notebook-commands"))
   assertTrue(rt.performNotebookAction(s,NotebookAction.Page("add")).isSuccess);rt.drainEngineLoopForTest()
   assertEquals(2,rt.latestSnapshot(s)!!.pages.size)
   assertTrue(rt.performNotebookAction(s,NotebookAction.Layer("add",name="Notes")).isSuccess);rt.drainEngineLoopForTest()
   assertTrue(rt.latestSnapshot(s)!!.layers.any { it.name=="Notes" })
   assertTrue(rt.performNotebookAction(s,NotebookAction.Outline("add",title="Section")).isSuccess);rt.drainEngineLoopForTest()
   assertEquals("Section",rt.notebookState(s).outline.single().title)
   assertTrue(rt.performNotebookAction(s,NotebookAction.InsertText("Кириллица 🌍")).isSuccess);rt.drainEngineLoopForTest()
   assertEquals(1,rt.latestSnapshot(s)!!.totalObjectCount)
   rt.trySend(AppCommand.Undo(s));rt.drainEngineLoopForTest()
   assertEquals(0,rt.latestSnapshot(s)!!.totalObjectCount)
  } finally {rt.closeForTest()}
 }
 @Test fun readonlyRejectsDocumentCommandsAndToolChangesAreEphemeral(@TempDir dir:Path) {
  val rt=Runtime.start(RuntimeConfig(storageRootPath=dir.toString())).getOrNull()!!
  try {
   val s=rt.openSessionSyncForTest(DocumentId("readonly-tools"))
   assertTrue(rt.performNotebookAction(s,NotebookAction.Tool("eraser")).isSuccess)
   assertEquals("eraser",rt.notebookState(s).tool)
   assertEquals(0,rt.latestSnapshot(s)!!.version.revision.value)
   val viewport=rt.latestViewport(s)!!
   rt.trySend(AppCommand.UpdateViewport(viewport.copy(isReadOnly=true)));rt.drainEngineLoopForTest()
   assertTrue(rt.performNotebookAction(s,NotebookAction.InsertText("blocked")).isFailure)
   assertEquals(0,rt.latestSnapshot(s)!!.totalObjectCount)
  } finally {rt.closeForTest()}
 }
}
