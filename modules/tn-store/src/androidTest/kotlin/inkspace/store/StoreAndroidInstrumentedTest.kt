package inkspace.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.TransactionId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocumentState
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.StickyPayload
import inkspace.core.ports.StorageReply
import inkspace.core.ports.StorageRequest
import inkspace.core.unit.DocRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StoreAndroidInstrumentedTest {

    @Test
    fun android_bundled_sqlite_wal_commit_and_reopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempDir = File(context.filesDir, "f03_android_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val docId = DocumentId("doc-android-001")
            val store1 = DocumentStore(rootDir = tempDir)
            val openRes = store1.execute(StorageRequest.Open(docId))
            assertTrue(openRes.isSuccess)

            val pragmas = store1.inspectPragmas(docId)
            assertEquals("wal", pragmas.journalMode.lowercase())
            assertEquals(2, pragmas.synchronous)
            assertEquals(1, pragmas.foreignKeys)

            val limits = Limits()
            val memState = DocumentState.createInitial(docId)
            val plan = EditPlan(
                base = memState.version,
                mutations = listOf(
                    InsertObject(
                        space = SpaceId("space-1"),
                        layer = LayerId("layer-1"),
                        id = ObjectId("obj-android-1"),
                        payload = StickyPayload(color = 1, content = "Android SQLite WAL"),
                        bounds = DocRect(0.0, 0.0, 40.0, 40.0)
                    )
                ),
                gesture = GestureId("g-android-1"),
                label = UndoLabel("Insert Android")
            )
            val applied = applyPlan(memState, validatePlan(memState.snapshot(), plan, limits).getOrNull()!!).getOrNull()!!
            val commitRes = store1.execute(
                StorageRequest.Commit(
                    revision = applied.revision,
                    transactionId = TransactionId("tx-android-1"),
                    expectedDurableBase = Revision(0L),
                    writeSet = applied.writeSet
                )
            )
            assertTrue(commitRes.isSuccess)
            store1.execute(StorageRequest.Close(docId))

            val store2 = DocumentStore(rootDir = tempDir)
            val reopen = store2.execute(StorageRequest.Open(docId, createIfMissing = false))
            assertTrue(reopen.isSuccess)
            val opened = reopen.getOrNull() as StorageReply.Opened
            assertEquals(Revision(1L), opened.durableRevision)
            assertTrue(store2.verifyIntegrity(docId))
            store2.execute(StorageRequest.Close(docId))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
