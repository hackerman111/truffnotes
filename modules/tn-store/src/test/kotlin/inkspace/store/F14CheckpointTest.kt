package inkspace.store

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.TextPayload
import inkspace.core.ports.StorageRequest
import inkspace.core.unit.DocRect
import inkspace.core.mutation.*
import inkspace.core.limits.Limits
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class F14CheckpointTest {
    @Test fun imported_checkpoint_reopens_exact_revision_without_overwrite(@TempDir root: Path) {
        val initial = DocumentState.createInitial(DocumentId("native" )).snapshot()
        val objectValue = SceneObject(ObjectId("text"), initial.pages[0].spaceId, initial.layers[0].id,
            DocRect(1.0,1.0,100.0,100.0), TextPayload("pending content"))
        val frozen = restoreSnapshot(SnapshotMetadata(initial.version.copy(revision=Revision(7)), initial.kind, initial.pages, initial.layers), sequenceOf(objectValue))
        val store = DocumentStore(root.toFile())
        val installed = store.installSnapshot(frozen) { error("No assets") }
        assertTrue(installed.isSuccess, installed.toString())
        assertTrue(store.execute(StorageRequest.Open(DocumentId("native"),false)).isSuccess)
        assertEquals(frozen, store.currentState(DocumentId("native"))!!.snapshot())
        store.execute(StorageRequest.Close(DocumentId("native")))
        assertTrue(store.installSnapshot(frozen) { error("No assets") }.isFailure)
        val reopen = DocumentStore(root.toFile())
        assertTrue(reopen.execute(StorageRequest.Open(DocumentId("native"),false)).isSuccess)
        assertEquals(frozen, reopen.currentState(DocumentId("native"))!!.snapshot())
        val editState = DocumentState.fromSnapshot(frozen)
        val plan = EditPlan(frozen.version, mutations=listOf(InsertObject(initial.pages[0].spaceId,initial.layers[0].id,ObjectId("second"),TextPayload("next"),DocRect(2.0,2.0,50.0,50.0))),gesture=GestureId("after-import"),label=UndoLabel("After import"))
        val valid = validatePlan(editState,plan,Limits()).getOrNull()!!
        val applied = applyPlan(editState,valid).getOrNull()!!
        assertTrue(reopen.execute(StorageRequest.Commit(applied.revision,transactionId=TransactionId("after-import"),expectedDurableBase=Revision(7),writeSet=applied.writeSet)).isSuccess)
        reopen.execute(StorageRequest.Close(DocumentId("native")))
        assertTrue(reopen.execute(StorageRequest.Open(DocumentId("native"),false)).isSuccess)
        assertEquals(Revision(8),reopen.currentState(DocumentId("native"))!!.version.revision)
        assertEquals(2,reopen.currentState(DocumentId("native"))!!.totalObjectCount)
        reopen.execute(StorageRequest.Close(DocumentId("native")))
    }

    @Test fun failed_asset_install_publishes_no_document(@TempDir root: Path) {
        val initial=DocumentState.createInitial(DocumentId("no-partial" )).snapshot()
        val value=SceneObject(ObjectId("image"),initial.pages[0].spaceId,initial.layers[0].id,DocRect(0.0,0.0,10.0,10.0),inkspace.core.payload.ImagePayload(AssetId("sha256:"+"0".repeat(64)),10,10))
        val snapshot=restoreSnapshot(SnapshotMetadata(initial.version,initial.kind,initial.pages,initial.layers),sequenceOf(value))
        val installed=DocumentStore(root.toFile()).installSnapshot(snapshot) { byteArrayOf(1,2,3).inputStream() }
        assertTrue(installed.isFailure)
        assertFalse(root.resolve("documents/no-partial").toFile().exists())
        assertTrue(root.resolve("documents").toFile().listFiles().orEmpty().isEmpty())
    }
}
