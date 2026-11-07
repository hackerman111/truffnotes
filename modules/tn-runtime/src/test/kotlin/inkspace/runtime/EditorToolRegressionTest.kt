package inkspace.runtime
import inkspace.core.id.*
import inkspace.core.ink.*
import inkspace.core.runtime.*
import inkspace.core.workspace.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*
class EditorToolRegressionTest {
    @Test fun toolbar_orientation_resize_keeps_center_and_cancels_draft(@TempDir dir: Path) {
        val rt = Runtime.start(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            val session = rt.openSessionSyncForTest(DocumentId("toolbar-resize"))
            rt.trySend(AppCommand.AttachSurface(session, SurfaceEpoch(1), 1000, 800, ResizeGeneration(1)))
            rt.drainEngineLoopForTest()
            val before = rt.latestViewport(session)!!
            val anchor = before.camera.viewToDoc(inkspace.core.unit.ViewPoint(500.0, 400.0))
            rt.submitInkBatch(InputBatch.fromSamples(session, 0, 1L, InputActionKind.DOWN,
                listOf(RealSample(50f, 50f, .5f, 0f, 100L))).getOrNull()!!)
            rt.drainEngineLoopForTest()
            assertTrue(rt.activeDraftStrokes(session).isNotEmpty())
            rt.trySend(AppCommand.AttachSurface(session, SurfaceEpoch(1), 800, 500, ResizeGeneration(2)))
            rt.drainEngineLoopForTest()
            val after = rt.latestViewport(session)!!
            val moved = after.camera.viewToDoc(inkspace.core.unit.ViewPoint(400.0, 250.0))
            assertEquals(anchor.x, moved.x, .01)
            assertEquals(anchor.y, moved.y, .01)
            assertTrue(rt.activeDraftStrokes(session).isEmpty())
            assertEquals(0, rt.latestSnapshot(session)!!.totalObjectCount)
        } finally { rt.closeForTest() }
    }
    @Test fun readonly_tape_does_not_commit(@TempDir dir: Path) {
        val rt = Runtime.start(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            val session = rt.openSessionSyncForTest(DocumentId("readonly-tape"))
            rt.performNotebookAction(session, NotebookAction.Tool("tape"))
            val viewport = rt.latestViewport(session)!!
            rt.trySend(AppCommand.UpdateViewport(viewport.copy(isReadOnly = true)))
            rt.drainEngineLoopForTest()
            for ((index, action) in listOf(InputActionKind.DOWN, InputActionKind.UP).withIndex()) {
                rt.submitInkBatch(InputBatch.fromSamples(session, 0, index + 1L, action,
                    listOf(RealSample(50f + index, 50f, .5f, 0f, 100L + index))).getOrNull()!!)
                rt.drainEngineLoopForTest()
            }
            assertEquals(0, rt.latestSnapshot(session)!!.totalObjectCount)
        } finally { rt.closeForTest() }
    }
    @Test fun globalCancelDiscardsAllNonInkPointers(@TempDir dir: Path) {
        val rt = Runtime.start(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            val session = rt.openSessionSyncForTest(DocumentId("cancel-tools"))
            rt.performNotebookAction(session, NotebookAction.Tool("shape"))
            var sequence = 0L
            fun input(pointer: Int, action: InputActionKind) {
                rt.submitInkBatch(InputBatch.fromSamples(session,pointer,++sequence,action,listOf(RealSample(50f,50f,.5f,0f,100+sequence))).getOrNull()!!)
                rt.drainEngineLoopForTest()
            }
            input(5,InputActionKind.DOWN); input(6,InputActionKind.DOWN)
            input(0,InputActionKind.CANCEL)
            input(5,InputActionKind.UP); input(6,InputActionKind.UP)
            assertEquals(0,rt.latestSnapshot(session)!!.totalObjectCount)
        } finally { rt.closeForTest() }
    }
    @Test fun tapeUsesItsOwnStyle(@TempDir dir: Path) {
        val rt = Runtime.start(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            val session = rt.openSessionSyncForTest(DocumentId("tape-style"))
            val style = ToolConfig(color=0xff1976d2.toInt(),widthPt=7.0,opacity=.4f)
            rt.performNotebookAction(session,NotebookAction.Settings(EditorSettings(tools=mapOf("tape" to style))))
            rt.performNotebookAction(session,NotebookAction.Tool("tape",EditorSettings(tools=mapOf("tape" to style))))
            for ((index,action) in listOf(InputActionKind.DOWN,InputActionKind.UP).withIndex()) {
                rt.submitInkBatch(InputBatch.fromSamples(session,0,index.toLong()+1,action,listOf(RealSample(50f+index,50f,.5f,0f,100+index.toLong()))).getOrNull()!!)
                rt.drainEngineLoopForTest()
            }
            val tape=rt.latestSnapshot(session)!!.directory.allResidentObjects().single().payload as inkspace.core.payload.TapePayload
            assertEquals(style.color,tape.patternColor); assertEquals(style.widthPt,tape.widthPt); assertEquals(style.opacity,tape.opacity)
        } finally { rt.closeForTest() }
    }
    @Test fun reopeningNotebookDoesNotReusePersistedStrokeIds(@TempDir dir: Path) {
        val config = RuntimeConfig(dir.toString())
        fun draw(rt: Runtime, expected: Int) {
            val s = rt.openSessionSyncForTest(DocumentId("reopen-ink"))
            for ((index, action) in listOf(InputActionKind.DOWN, InputActionKind.UP).withIndex()) {
                rt.submitInkBatch(InputBatch.fromSamples(s,0,index.toLong()+1,action,listOf(RealSample(50f+index,50f,.5f,0f,100+index.toLong()))).getOrNull()!!)
                rt.drainEngineLoopForTest()
            }
            assertEquals(expected, rt.latestSnapshot(s)!!.totalObjectCount)
        }
        Runtime.start(config).getOrNull()!!.let { rt -> try { draw(rt,1) } finally { rt.closeForTest() } }
        Runtime.start(config).getOrNull()!!.let { rt -> try { draw(rt,2) } finally { rt.closeForTest() } }
    }
    @Test fun hardwareEraserUsesTheObjectEraserWithoutSwitchingSelectedTool(@TempDir dir: Path) {
        val rt = Runtime.start(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            val s = rt.openSessionSyncForTest(DocumentId("hardware-eraser"))
            var seq = 0L
            fun stroke(tool: InputToolType) {
                for (action in listOf(InputActionKind.DOWN, InputActionKind.UP)) {
                    seq++
                    rt.submitInkBatch(InputBatch.fromSamples(s,0,seq,action,listOf(RealSample(50f,50f,.5f,0f,100+seq)),toolType = tool).getOrNull()!!)
                    rt.drainEngineLoopForTest()
                }
            }
            stroke(InputToolType.STYLUS)
            assertEquals(1, rt.latestSnapshot(s)!!.totalObjectCount)
            stroke(InputToolType.ERASER)
            assertEquals(0, rt.latestSnapshot(s)!!.totalObjectCount)
            assertEquals("pen", rt.notebookState(s).tool)
        } finally { rt.closeForTest() }
    }
    @Test fun laserIsEphemeralAndVisible(@TempDir dir: Path) {
        val rt = Runtime.start(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            val s = rt.openSessionSyncForTest(DocumentId("laser"))
            assertTrue(rt.performNotebookAction(s, NotebookAction.Tool("laser")).isSuccess)
            val batch = InputBatch.fromSamples(s,0,1,InputActionKind.DOWN,listOf(RealSample(50f,50f,.5f,0f,100))).getOrNull()!!
            rt.submitInkBatch(batch); rt.drainEngineLoopForTest()
            assertEquals(0, rt.latestSnapshot(s)!!.totalObjectCount)
            assertEquals(0, rt.latestSnapshot(s)!!.version.revision.value)
            assertTrue(rt.sceneForRendering(s)!!.frozenEphemeralOverlays.isNotEmpty())
        } finally { rt.closeForTest() }
    }
    @Test fun switchingToolsCancelsUnfinishedStroke(@TempDir dir: Path) {
        val rt = Runtime.start(RuntimeConfig(dir.toString())).getOrNull()!!
        try {
            val s = rt.openSessionSyncForTest(DocumentId("tool-switch"))
            fun input(action: InputActionKind, seq: Long) {
                rt.submitInkBatch(InputBatch.fromSamples(s,0,seq,action,listOf(RealSample(50f,50f,.5f,0f,100+seq))).getOrNull()!!)
                rt.drainEngineLoopForTest()
            }
            input(InputActionKind.DOWN,1)
            assertTrue(rt.activeDraftStrokes(s).isNotEmpty())
            rt.performNotebookAction(s, NotebookAction.Tool("eraser"))
            assertTrue(rt.activeDraftStrokes(s).isEmpty())
            rt.performNotebookAction(s, NotebookAction.Tool("pen"))
            input(InputActionKind.UP,2)
            assertEquals(0, rt.latestSnapshot(s)!!.totalObjectCount)
        } finally { rt.closeForTest() }
    }
}
