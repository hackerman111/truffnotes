package inkspace.runtime

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.mutation.*
import inkspace.core.payload.*
import inkspace.core.ports.*
import inkspace.core.runtime.*
import inkspace.core.codec.CoreCodec
import inkspace.core.unit.*
import inkspace.exchange.*
import inkspace.store.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.*
import kotlin.test.*

class F14RecoveryTest {
    @Test fun cold_records_remain_in_the_frozen_generation(@TempDir root: File) {
        val id=DocumentId("cold")
        val initial=DocumentState.createInitial(id).snapshot()
        val item=SceneObject(ObjectId("cold-object"),initial.pages[0].spaceId,initial.layers[0].id,DocRect(1.0,1.0,20.0,20.0),TextPayload("Frozen cold text"))
        val snapshot=restoreSnapshot(SnapshotMetadata(initial.version.copy(revision=Revision(4)),initial.kind,initial.pages,initial.layers),sequenceOf(item))
        val store=DocumentStore(root)
        assertTrue(store.installSnapshot(snapshot) {error("No assets")}.isSuccess)
        store.execute(StorageRequest.Open(id,false))
        val state=DocumentState.fromSnapshot(snapshot)
        state.registerNonResidentObject(item.id,item.space,item.layer,item.bounds,item.version)
        var closed=false
        RecoveryLease(state.snapshot(),store,root,AutoCloseable {closed=true}).use { lease ->
            assertEquals(listOf(item),lease.records().toList())
            var admitted=0L
            assertEquals(snapshot,lease.residentSnapshot {admitted=it})
            assertTrue(admitted>=item.estimatedMemoryBytes())
            assertFailsWith<IllegalStateException> {lease.residentSnapshot {error("Budget exhausted")}}
            val changed=DocumentState.fromSnapshot(snapshot)
            val plan=EditPlan(changed.version,mutations=listOf(UpdatePayload(item.id,TextPayload("Later"),item.bounds)),gesture=GestureId("later"),label=UndoLabel("Later"))
            val applied=applyPlan(changed,validatePlan(changed,plan,inkspace.core.limits.Limits()).getOrNull()!!).getOrNull()!!
            assertTrue(store.execute(StorageRequest.Commit(applied.revision,transactionId=TransactionId("later"),expectedDurableBase=Revision(4),writeSet=applied.writeSet)).isSuccess)
            assertEquals(listOf(item),lease.records().toList())
        }
        assertTrue(closed)
        store.execute(StorageRequest.Close(id))
    }
    @Test fun recovery_pending_disk_full(@TempDir root: File) {
        val faults = FaultInjectionController()
        val store = DocumentStore(root,faults)
        val runtime = Runtime.start(RuntimeConfig(root.path),PlatformPorts(storagePort=store)).getOrNull()!!
        val id = DocumentId("disk-full")
        try {
            val session = runtime.openSessionSyncForTest(id)
            val asset = store.stageAsset(id,"pending",byteArrayOf(1,2,3,4)).getOrNull()!!
            val initial = runtime.latestSnapshot(session)!!
            fun edit(mutations: List<Mutation>, staged: List<StagedAssetRef> = emptyList()) {
                val snapshot = runtime.latestSnapshot(session)!!
                val plan = EditPlan(snapshot.version,mutations=mutations,gesture=GestureId("g${snapshot.version.revision.value}"),label=UndoLabel("Pending"))
                assertTrue(runtime.trySend(AppCommand.SubmitEdit(session,plan,stagedAssets=staged)).isSuccess)
                runtime.drainEngineLoopForTest()
            }
            faults.nextFault = FaultPoint.DISK_FULL_ON_DB_COMMIT
            val bounds = DocRect(1.0,1.0,40.0,40.0)
            edit(listOf(InsertObject(initial.pages.first().spaceId,initial.layers.first().id,ObjectId("stroke"),StrokePayload("ball",0xff000000.toInt(),2.0,DocPoint(1.0,1.0),listOf(StrokeSamplePoint(0f,0f,.5f),StrokeSamplePoint(20f,20f,.5f))),bounds)))
            edit(listOf(InsertObject(initial.pages.first().spaceId,initial.layers.first().id,ObjectId("image"),ImagePayload(asset.assetId,2,2),bounds)),listOf(asset))
            edit(listOf(UpdatePayload(ObjectId("stroke"),StrokePayload("ball",0xff1976d2.toInt(),7.0,DocPoint(1.0,1.0),listOf(StrokeSamplePoint(0f,0f,.5f),StrokeSamplePoint(20f,20f,.5f))),bounds)))
            val before = runtime.latestSaveStatus(session)!!
            val frozen = runtime.latestSnapshot(session)!!
            val bytes = ByteArrayOutputStream()
            store.execute(StorageRequest.Collect(id))
            assertTrue(store.assetFile(id,asset.assetId).isFile)
            runtime.acquireRecovery(session).use { lease ->
                // Pin keeps a pending, unreferenced durable asset alive through concurrent collection.
                store.execute(StorageRequest.Collect(id))
                NativeArchive.write(lease,bytes)
                assertTrue(store.assetFile(id,asset.assetId).isFile)
            }
            val records = mutableListOf<SceneObject>()
            val assets = linkedMapOf<String,ByteArrayOutputStream>()
            lateinit var header: NativeDocument
            NativeArchive.read(bytes.toByteArray().inputStream(),object: ImportSink {
                override fun document(value: NativeDocument) { header=value }
                override fun record(path: String, bytes: ByteArray) { records += CoreCodec.decodeFromCbor<SceneObject>(bytes).getOrNull()!! }
                override fun asset(path: String): OutputStream = ByteArrayOutputStream().also { assets[path]=it }
                override fun commit(manifest: NativeManifest) = Unit
                override fun abort() = Unit
            })
            val recovered = restoreSnapshot(SnapshotMetadata(header.version,header.kind,header.pages,header.layers),records.asSequence())
            assertEquals(frozen,recovered)
            assertContentEquals(byteArrayOf(1,2,3,4),assets.values.single().toByteArray())
            val destination = DocumentStore(File(root,"available-output"))
            assertTrue(destination.installSnapshot(recovered) { assets.getValue(NativeArchive.assetPath(it.value)).toByteArray().inputStream() }.isSuccess)
            assertTrue(destination.execute(StorageRequest.Open(id,false)).isSuccess)
            assertEquals(frozen,destination.currentState(id)!!.snapshot())
            destination.execute(StorageRequest.Close(id))
            assertEquals(before.durableRevision,runtime.latestSaveStatus(session)!!.durableRevision)
            assertEquals(Revision(0),store.durableRevision(id))
        } finally { runtime.closeForTest() }
    }
}
