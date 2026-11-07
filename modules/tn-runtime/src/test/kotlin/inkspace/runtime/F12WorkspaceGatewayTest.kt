package inkspace.runtime

import inkspace.core.workspace.*
import inkspace.core.runtime.*
import inkspace.core.outcome.Outcome
import inkspace.core.ports.RequestId
import inkspace.core.error.SubmitError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class F12WorkspaceGatewayTest {
    @TempDir lateinit var directory: Path

    @Test fun fullNativePreviewSupportsColdPayloadsWithoutMaterializingThem() {
        val key=inkspace.core.model.SessionKey(inkspace.core.id.DocumentId("cold-preview"),inkspace.core.id.SessionEpoch(1))
        val model=inkspace.core.model.DocumentState.createInitial(key.document)
        model.registerNonResidentObject(inkspace.core.id.ObjectId("cold"),model.pages.first().spaceId,model.layers.first().id,inkspace.core.unit.DocRect(0.0,0.0,10.0,10.0))
        val app=object:AppGateway {
            override fun trySend(command:AppCommand):Outcome<RequestId,SubmitError> = Outcome.Success(RequestId(1))
            override fun pollEvents(max:Int)=EventBatch(emptyList(),emptyList())
            override fun closeAsync():Outcome<RequestId,SubmitError> = Outcome.Success(RequestId(2))
        }
        val gateway=WorkspaceGateway(directory.resolve("preview.json").toFile(),app,featureState={NotebookDocumentState(snapshot=model.snapshot())}) {key}
        val preview=gateway.previewExport(ExportOptions())
        assertTrue(preview.isSuccess,preview.toString())
        assertTrue(preview.getOrNull()!!.contains("1 editable objects"))
        assertEquals(inkspace.core.read.ResidencyState.NotResident,model.objectState(inkspace.core.id.ObjectId("cold")))
    }
    @Test fun catalogAndColdTabsReloadWithoutResidentDocuments() {
        val app = object : AppGateway {
            override fun trySend(command: AppCommand): Outcome<RequestId, SubmitError> = Outcome.Success(RequestId(1))
            override fun pollEvents(max: Int) = EventBatch(emptyList(), emptyList(), 0)
            override fun closeAsync(): Outcome<RequestId, SubmitError> = Outcome.Success(RequestId(2))
        }
        val file = directory.resolve("library.json").toFile()
        val first = WorkspaceGateway(file, app) { null }
        assertTrue(first.submit(LibraryAction.Create("notes", "My notebook")).isSuccess)
        assertTrue(first.dispatch(ViewAction.Open("notes")).accepted)
        val restored = WorkspaceGateway(file, app) { null }
        assertEquals("My notebook", restored.entries(LibrarySection.DOCUMENTS).single().name)
        assertEquals("notes", restored.workspace().active!!.documentId)
        assertEquals(1, restored.workspace().tabs.size)
    }

    @Test fun workspaceWriteFailureDoesNotDispatchOrChangeTabs() {
        val commands = mutableListOf<AppCommand>()
        val app = object : AppGateway {
            override fun trySend(command: AppCommand): Outcome<RequestId, SubmitError> {
                commands += command
                return Outcome.Success(RequestId(1))
            }
            override fun pollEvents(max: Int) = EventBatch(emptyList(), emptyList())
            override fun closeAsync(): Outcome<RequestId, SubmitError> = Outcome.Success(RequestId(2))
        }
        val gateway = WorkspaceGateway(directory.resolve("library.json").toFile(), app) { null }
        gateway.entries(LibrarySection.DOCUMENTS)
        gateway.submit(LibraryAction.Create("notes", "Notes"))
        directory.resolve("workspace.json.pending").toFile().mkdir()
        assertFalse(gateway.dispatch(ViewAction.Open("notes")).accepted)
        assertTrue(gateway.workspace().tabs.isEmpty())
        assertTrue(commands.isEmpty())
    }

    @Test fun notebookDimensionsBecomeCoreMutationAndPaperDefaultsAreStored() {
        val commands = mutableListOf<AppCommand>()
        val app = object : AppGateway {
            override fun trySend(command: AppCommand): Outcome<RequestId, SubmitError> {
                commands += command
                return Outcome.Success(RequestId(1))
            }
            override fun pollEvents(max: Int) = EventBatch(emptyList(), emptyList())
            override fun closeAsync(): Outcome<RequestId, SubmitError> = Outcome.Success(RequestId(2))
        }
        val featureActions = mutableListOf<NotebookAction>()
        val gateway = WorkspaceGateway(directory.resolve("library.json").toFile(), app, featureDispatch = { _, action ->
            featureActions += action
            Outcome.Success(RequestId(1))
        }) { null }
        assertTrue(gateway.submit(LibraryAction.Create("ruled", "Notes", defaults = NotebookDefaults(paper = "ruled"))).isSuccess)
        assertTrue(gateway.submit(LibraryAction.Create("notes", "Notes", defaults = NotebookDefaults(widthPt = 300.0, heightPt = 400.0))).isSuccess)
        gateway.dispatch(ViewAction.Open("notes"))
        val session = inkspace.core.model.SessionKey(inkspace.core.id.DocumentId("notes"), inkspace.core.id.SessionEpoch(1))
        val snapshot = inkspace.core.model.DocumentState.createInitial(session.document).snapshot()
        gateway.onRuntimeEvents(listOf(AppEvent.SessionOpened(session, snapshot,
            SaveStatus(session, SaveStatusKind.SAVED, inkspace.core.id.Revision(0), inkspace.core.id.Revision(0), 0, 0),
            ViewportState(session))))
        val resize = featureActions.filterIsInstance<NotebookAction.Page>().last { it.command == "resize" }
        assertEquals(300.0, resize.widthPt)
        assertEquals(400.0, resize.heightPt)
    }

    private class RecordingGateway : AppGateway {
        val commands = mutableListOf<AppCommand>()
        var reject = false
        override fun trySend(command: AppCommand): Outcome<RequestId, SubmitError> {
            if (reject) return Outcome.Failure(SubmitError.InvalidPayload("Queue rejected"))
            commands += command
            return Outcome.Success(RequestId(commands.size.toLong()))
        }
        override fun pollEvents(max: Int) = EventBatch(emptyList(), emptyList())
        override fun closeAsync(): Outcome<RequestId, SubmitError> = Outcome.Success(RequestId(0))
    }

    private fun opened(id: String): AppEvent.SessionOpened {
        val session = inkspace.core.model.SessionKey(inkspace.core.id.DocumentId(id), inkspace.core.id.SessionEpoch(1))
        val snapshot = inkspace.core.model.DocumentState.createInitial(session.document).snapshot()
        return AppEvent.SessionOpened(session, snapshot,
            SaveStatus(session, SaveStatusKind.SAVED, inkspace.core.id.Revision(0), inkspace.core.id.Revision(0), 0, 0),
            ViewportState(session))
    }

    @Test fun overlappingSwitchesWaitForMatchingOpenedEvent() {
        val app = RecordingGateway()
        var current: inkspace.core.model.SessionKey? = null
        val gateway = WorkspaceGateway(directory.resolve("library.json").toFile(), app) { current }
        for (id in listOf("a", "b", "c")) gateway.submit(LibraryAction.Create(id, id))
        assertTrue(gateway.dispatch(ViewAction.Open("a")).accepted)
        assertFalse(gateway.dispatch(ViewAction.Open("b")).accepted)
        gateway.onRuntimeEvents(listOf(opened("unrelated")))
        assertFalse(gateway.dispatch(ViewAction.Open("b")).accepted)
        val event = opened("a")
        current = event.session
        gateway.onRuntimeEvents(listOf(event))
        assertTrue(gateway.dispatch(ViewAction.Open("b")).accepted)
        assertFalse(gateway.dispatch(ViewAction.Open("c")).accepted)
        assertEquals(listOf("a", "b"), app.commands.filterIsInstance<AppCommand.OpenSession>().map { it.documentId.value })
        assertEquals(1, app.commands.filterIsInstance<AppCommand.CloseSession>().size)
    }

    @Test fun restoredActiveTabOpensWhenRuntimeStillHasAnotherDocument() {
        val app = RecordingGateway()
        val file = directory.resolve("library.json").toFile()
        val first = WorkspaceGateway(file, app) { null }
        first.submit(LibraryAction.Create("notes", "Notes"))
        first.dispatch(ViewAction.Open("notes"))
        val other = opened("default").session
        val restored = WorkspaceGateway(file, app) { other }
        restored.entries(LibrarySection.DOCUMENTS)
        app.commands.clear()
        val active = restored.workspace().activeTab!!
        assertTrue(restored.dispatch(ViewAction.Switch(active)).accepted)
        assertEquals("notes", app.commands.filterIsInstance<AppCommand.OpenSession>().single().documentId.value)
    }

    @Test fun queueRejectionRollsBackPersistedWorkspace() {
        val app = RecordingGateway()
        val file = directory.resolve("library.json").toFile()
        val gateway = WorkspaceGateway(file, app) { null }
        gateway.submit(LibraryAction.Create("notes", "Notes"))
        app.reject = true
        assertFalse(gateway.dispatch(ViewAction.Open("notes")).accepted)
        assertTrue(gateway.workspace().tabs.isEmpty())
        val restored = WorkspaceGateway(file, app) { null }
        restored.entries(LibrarySection.DOCUMENTS)
        assertTrue(restored.workspace().tabs.isEmpty())
    }

    @Test fun toolsUseRealFeatureCallbackAndTextPinControlsInsertTransition() {
        val app = RecordingGateway()
        var current: inkspace.core.model.SessionKey? = null
        val actions = mutableListOf<NotebookAction>()
        val gateway = WorkspaceGateway(directory.resolve("library.json").toFile(), app, featureDispatch = { _, action ->
            actions += action; Outcome.Success(RequestId(1))
        }) { current }
        gateway.submit(LibraryAction.Create("notes", "Notes"))
        gateway.dispatch(ViewAction.Open("notes"))
        val event = opened("notes"); current = event.session; gateway.onRuntimeEvents(listOf(event)); actions.clear()
        assertTrue(gateway.dispatch(ViewAction.Command("eraser")).accepted)
        assertEquals("eraser", (actions.last() as NotebookAction.Tool).id)
        assertTrue(gateway.perform(NotebookAction.InsertText("Hello")).isSuccess)
        assertEquals("lasso", gateway.workspace().active!!.tool)
        gateway.perform(NotebookAction.Settings(EditorSettings(textPinned = true)))
        gateway.perform(NotebookAction.Tool("text"))
        gateway.perform(NotebookAction.InsertText("Pinned"))
        assertEquals("text", gateway.workspace().active!!.tool)
        gateway.dispatch(ViewAction.Command("readonly"))
        val before = actions.size
        assertFalse(gateway.perform(NotebookAction.Selection("delete")).isSuccess)
        assertEquals(before, actions.size)
        gateway.close()
    }

    @Test fun libraryImportCreatesVisibleTargetAndDispatchesSourceOffMainAfterOpen() {
        val app = RecordingGateway()
        val current = opened("default").session
        val imported = java.util.concurrent.CountDownLatch(1)
        val received = java.util.concurrent.atomic.AtomicReference<NotebookAction.ImportResource>()
        val workerName = java.util.concurrent.atomic.AtomicReference<String>()
        val gateway = WorkspaceGateway(directory.resolve("library.json").toFile(), app, featureDispatch = { _, action ->
            if (action is NotebookAction.ImportResource) { received.set(action); workerName.set(Thread.currentThread().name); imported.countDown() }
            Outcome.Success(RequestId(1))
        }) { current }
        try {
            val result = gateway.perform(NotebookAction.ImportResource("content://documents/report.pdf", "application/pdf", true, "imported"))
            assertTrue(result.isSuccess)
            assertEquals("imported", gateway.entries(LibrarySection.DOCUMENTS).single().id)
            assertEquals("imported", gateway.workspace().active!!.documentId)
            assertEquals(1L, imported.count)
            gateway.onRuntimeEvents(listOf(opened("imported")))
            assertTrue(imported.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(received.get().asDocument)
            assertEquals("imported", received.get().targetDocumentId)
            assertEquals("notebook-import", workerName.get())
        } finally { gateway.close() }
    }
}
