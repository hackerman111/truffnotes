package inkspace.runtime

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.UndoLabel
import inkspace.core.model.DocVersion
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.MoveObject
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.PlatformPorts
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoreTransactionsTest {

    @Test
    fun runtime_executes_atomic_plan_undo_redo_through_single_core_reducer(@TempDir tempDir: File) {
        val config = RuntimeConfig(storageRootPath = tempDir.absolutePath)
        val runtime = Runtime.start(config, PlatformPorts()).getOrNull()!!

        val docId = DocumentId("doc-runtime-tx")
        val session = runtime.openSessionSyncForTest(docId)
        val initialFingerprint = runtime.latestSnapshot(session)!!.contentFingerprint()

        val insertPlan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            preconditions = emptyList(),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("stroke-1"),
                    payload = StrokePayload(
                        brushId = "ball",
                        color = 0xFF000000.toInt(),
                        baseWidthPt = 2.0,
                        origin = DocPoint(10.0, 20.0),
                        samples = listOf(
                            StrokeSamplePoint(0f, 0f, 0.5f),
                            StrokeSamplePoint(30f, 40f, 0.8f)
                        )
                    ),
                    bounds = DocRect(10.0, 20.0, 40.0, 60.0)
                )
            ),
            gesture = GestureId("g-insert"),
            label = UndoLabel("insert-stroke")
        )
        assertTrue(runtime.trySend(AppCommand.SubmitEdit(session, insertPlan)).isSuccess)
        runtime.drainEngineLoopForTest()

        val movePlan = EditPlan(
            base = DocVersion(docId, Revision(1L)),
            preconditions = emptyList(),
            mutations = listOf(
                MoveObject(id = ObjectId("stroke-1"), deltaX = 15.0, deltaY = -5.0)
            ),
            gesture = GestureId("g-move"),
            label = UndoLabel("move-stroke")
        )
        assertTrue(runtime.trySend(AppCommand.SubmitEdit(session, movePlan)).isSuccess)
        runtime.drainEngineLoopForTest()

        val afterMoveSnap = runtime.latestSnapshot(session)!!
        assertEquals(Revision(2L), afterMoveSnap.version.revision)
        val finalFingerprint = afterMoveSnap.contentFingerprint()

        // Undo twice -> returns to exact initial document model
        assertTrue(runtime.trySend(AppCommand.Undo(session)).isSuccess)
        runtime.drainEngineLoopForTest()
        assertTrue(runtime.trySend(AppCommand.Undo(session)).isSuccess)
        runtime.drainEngineLoopForTest()

        val afterFullUndo = runtime.latestSnapshot(session)!!
        assertEquals(Revision(4L), afterFullUndo.version.revision)
        assertEquals(0, afterFullUndo.totalObjectCount)
        assertEquals(initialFingerprint, afterFullUndo.contentFingerprint())

        // Redo twice -> returns to exact final document model
        assertTrue(runtime.trySend(AppCommand.Redo(session)).isSuccess)
        runtime.drainEngineLoopForTest()
        assertTrue(runtime.trySend(AppCommand.Redo(session)).isSuccess)
        runtime.drainEngineLoopForTest()

        val afterFullRedo = runtime.latestSnapshot(session)!!
        assertEquals(Revision(6L), afterFullRedo.version.revision)
        assertEquals(1, afterFullRedo.totalObjectCount)
        assertEquals(finalFingerprint, afterFullRedo.contentFingerprint())

        runtime.closeForTest()
    }
}
