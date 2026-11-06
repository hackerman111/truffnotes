package inkspace.runtime

import inkspace.core.workspace.*
import inkspace.core.id.DocumentId
import inkspace.core.model.SessionKey
import inkspace.core.id.SessionEpoch
import inkspace.core.id.GestureId
import inkspace.core.id.UndoLabel
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.ResizePage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import inkspace.core.outcome.Outcome
import inkspace.core.runtime.*
import inkspace.library.LibraryService
import inkspace.library.ThumbnailJobs
import inkspace.core.error.FeatureError
import inkspace.core.ports.RequestId
import inkspace.workspace.Workspace
import inkspace.workspace.CommandRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Called from the UI IO dispatcher for durable library operations. */
class WorkspaceGateway(
    catalogFile: File,
    private val app: AppGateway,
    private val featureDispatch: (SessionKey, NotebookAction) -> Outcome<RequestId, FeatureError> = { _, _ -> Outcome.Failure(FeatureError.InvalidParameters("Notebook editor unavailable")) },
    private val featureState: (SessionKey) -> NotebookDocumentState = { NotebookDocumentState() },
    thumbnailProvider: (String, Int) -> Outcome<NotebookThumbnail, FeatureError> = { _, _ -> Outcome.Failure(FeatureError.InvalidParameters("Thumbnail provider unavailable")) },
    private val copyNotebook: (String, String, NotebookDefaults) -> Outcome<Unit, FeatureError> = { _, _, _ -> Outcome.Failure(FeatureError.InvalidParameters("Template copier unavailable")) },
    private val pdfQuery: (SessionKey, PdfQuery) -> Outcome<PdfViewData, FeatureError> = { _, _ -> Outcome.Failure(FeatureError.InvalidParameters("PDF reader unavailable")) },
    private val session: () -> SessionKey?
) : NotebookGateway {
    private val thumbnails = ThumbnailJobs(thumbnailProvider)
    private val pendingImports = ConcurrentHashMap<String, NotebookAction.ImportResource>()
    private val importError = AtomicReference<String?>(null)
    private val importWorker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "notebook-import").apply { isDaemon = true } })
    private val library by lazy { LibraryService(catalogFile) }
    private val workspaceFile = File(catalogFile.parentFile, "workspace.json")
    private var loaded = false
    @Volatile private var views = Workspace()

    private fun loadWorkspace() {
        if (loaded) return
        if (workspaceFile.exists()) {
            require(workspaceFile.length() <= 8L * 1024 * 1024) { "Workspace metadata exceeds budget" }
            val saved = Json.decodeFromString<WorkspaceState>(workspaceFile.readText())
            require(saved.tabs.size <= 1000 && saved.closedTabs.size <= 100 && saved.tabs.map { it.id }.toSet().size == saved.tabs.size)
            views = Workspace(saved)
        }
        loaded = true
    }

    private fun persistWorkspace() {
        val encoded = Json.encodeToString(views.state).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 8 * 1024 * 1024) { "Workspace metadata exceeds budget" }
        workspaceFile.parentFile?.mkdirs()
        val pending = File(workspaceFile.path + ".pending")
        FileOutputStream(pending).use { out ->
            out.write(encoded)
            out.fd.sync()
        }
        Files.move(pending.toPath(), workspaceFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized override fun entries(section: LibrarySection): List<LibraryEntry> {
        loadWorkspace()
        return library.query(section)
    }
    @Volatile private var pendingOpen: String? = null
    @Volatile private var pendingClose: SessionKey? = null
    private val pendingDefaults = ConcurrentHashMap<String, NotebookDefaults>()
    @Synchronized override fun submit(action: LibraryAction): Outcome<OperationId, LibraryError> {
        return try { library.submit(action) } catch (failure: Exception) {
            Outcome.Failure(LibraryError(failure.message ?: "Cannot read catalog"))
        }
    }

    fun onRuntimeEvents(events: List<AppEvent>) {
        for (event in events) {
            if (event is AppEvent.SessionClosed && event.session == pendingClose) pendingClose = null
            if (event is AppEvent.SessionOpened && event.session.document.value == pendingOpen) pendingOpen = null
            if (event is AppEvent.ViewportChanged && views.state.active?.documentId == event.viewport.session.document.value) {
                views.dispatch(ViewAction.Camera(event.viewport.camera))
                views.dispatch(ViewAction.Selection(event.viewport.selectedObjectIds.map { it.value }.toSet()))
                event.viewport.activePageId?.let { views.dispatch(ViewAction.ActivePage(it.value)) }
            }
            if (event is AppEvent.SnapshotPublished) {
                thumbnails.invalidate(event.session.document.value)
                if (views.state.active?.documentId == event.session.document.value) views.dispatch(ViewAction.Dirty(event.saveStatus.hasUnsavedChanges))
            }
            if (event is AppEvent.SaveStatusChanged && views.state.active?.documentId == event.saveStatus.session.document.value) {
                views.dispatch(ViewAction.Dirty(event.saveStatus.hasUnsavedChanges))
            }
            if (event !is AppEvent.SessionOpened) continue
            if (views.state.active?.documentId == event.session.document.value) views.dispatch(ViewAction.Dirty(event.saveStatus.hasUnsavedChanges))
            pendingImports.remove(event.session.document.value)?.let { resource ->
                try {
                    importWorker.execute {
                        val imported = featureDispatch(event.session, resource)
                        if (imported is Outcome.Failure) importError.set(imported.error.toString())
                    }
                } catch (_: RejectedExecutionException) {
                    importError.set("Import queue full; notebook remains available for retry")
                }
            }
            val tab = views.state.active?.takeIf { it.documentId == event.session.document.value }
            if (tab != null) {
                featureDispatch(event.session, NotebookAction.Settings(tab.settings))
                featureDispatch(event.session, NotebookAction.Tool(tab.tool, tab.settings))
                tab.activePage?.let { featureDispatch(event.session, NotebookAction.SelectPage(it)) }
                tab.activeLayer?.let { featureDispatch(event.session, NotebookAction.SelectLayer(it)) }
            }
            val defaults = pendingDefaults.remove(event.session.document.value) ?: continue
            val page = event.snapshot.pages.singleOrNull() ?: continue
            if (event.snapshot.version.revision.value != 0L) continue
            featureDispatch(event.session, NotebookAction.Page("background", page.id.value, defaults = defaults))
            if (page.widthPt == defaults.widthPt && page.heightPt == defaults.heightPt) continue
            featureDispatch(event.session, NotebookAction.Page("resize", page.id.value,
                widthPt = defaults.widthPt, heightPt = defaults.heightPt, policy = "KEEP_GEOMETRY"))
        }
    }
    override fun queryPdf(query: PdfQuery): Outcome<PdfViewData, FeatureError> {
        val current = session() ?: return Outcome.Failure(FeatureError.InvalidParameters("No active notebook"))
        return pdfQuery(current, query)
    }
    override fun editorState(): NotebookDocumentState = session()?.let(featureState) ?: NotebookDocumentState()

    override fun thumbnail(documentId: String, maxEdge: Int) = thumbnails.get(documentId, maxEdge)
    override fun takeError(): String? = importError.getAndSet(null)
    override fun close() { thumbnails.close(); importWorker.shutdownNow() }

    @Synchronized override fun copyFromTemplate(sourceId: String, id: String, name: String, defaults: NotebookDefaults): Outcome<OperationId, LibraryError> {
        val source = library.entries().find { it.id == sourceId && it.template && !it.trashed }
            ?: return Outcome.Failure(LibraryError("Template unavailable"))
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(id) || name.isBlank() || library.entries().any { it.id == id }) {
            return Outcome.Failure(LibraryError("Invalid target"))
        }
        val copied = copyNotebook(source.id, id, defaults)
        if (copied is Outcome.Failure) return Outcome.Failure(LibraryError(copied.error.toString()))
        return library.submit(LibraryAction.Create(id, name, defaults = defaults))
    }

    @Synchronized override fun perform(action: NotebookAction): Outcome<RequestId, FeatureError> {
        loadWorkspace()
        val current = session() ?: return Outcome.Failure(FeatureError.InvalidParameters("No active document"))
        if (action is NotebookAction.ImportResource && action.asDocument) {
            if (pendingOpen != null || pendingClose != null || pendingImports.size >= 4) return Outcome.Failure(FeatureError.InvalidParameters("Import or document switch pending"))
            val id = action.targetDocumentId ?: UUID.randomUUID().toString()
            if (!Regex("[A-Za-z0-9_-]{1,128}").matches(id)) return Outcome.Failure(FeatureError.InvalidParameters("Invalid import document ID"))
            val fallback = if (action.mimeType == "application/pdf") "Imported PDF" else "Imported image"
            val title = runCatching { java.net.URI(action.uri).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.take(1024) }.getOrNull() ?: fallback
            val created = library.submit(LibraryAction.Create(id, title))
            if (created is Outcome.Failure) return Outcome.Failure(FeatureError.InvalidParameters(created.error.message))
            pendingImports[id] = action.copy(targetDocumentId = id)
            val opened = dispatch(ViewAction.Open(id))
            if (!opened.accepted) {
                pendingImports.remove(id)
                return Outcome.Failure(FeatureError.InvalidParameters("Imported notebook created; retry opening it after saving"))
            }
            return Outcome.Success(RequestId(0))
        }
        if (pendingOpen != null || pendingClose != null) return Outcome.Failure(FeatureError.InvalidParameters("Document switch pending"))
        val active = views.state.active ?: if (action is NotebookAction.ImportResource) TabState("import", current.document.value)
            else return Outcome.Failure(FeatureError.InvalidParameters("No active tab"))
        val viewOnly = action is NotebookAction.SelectPage || action is NotebookAction.SelectLayer || action is NotebookAction.Tool || action is NotebookAction.Settings
        if ((active.readOnly || app.latestViewport(current)?.isReadOnly == true) && !viewOnly) {
            return Outcome.Failure(FeatureError.InvalidParameters("Read-only document"))
        }
        val result = featureDispatch(current, action)
        if (result is Outcome.Success) {
            when (action) {
                is NotebookAction.SelectPage -> views.dispatch(ViewAction.ActivePage(action.pageId))
                is NotebookAction.SelectLayer -> views.dispatch(ViewAction.ActiveLayer(action.layerId))
                is NotebookAction.Tool -> views.dispatch(ViewAction.Command(action.id))
                is NotebookAction.Settings -> views.dispatch(ViewAction.Settings(action.settings))
                is NotebookAction.InsertText -> if (!active.settings.textPinned) {
                    featureDispatch(current, NotebookAction.Tool("lasso", active.settings))
                    views.dispatch(ViewAction.Command("lasso"))
                }
                else -> Unit
            }
            persistWorkspace()
        }
        return result
    }

    override fun resolveKey(key: String, ctrl: Boolean, shift: Boolean, textFocused: Boolean) =
        CommandRegistry.keyboard(key, ctrl, shift, textFocused)
    override fun workspace() = views.state

    @Synchronized override fun dispatch(action: ViewAction): ViewUpdate {
        loadWorkspace()
        if (pendingOpen != null || pendingClose != null) return ViewUpdate(views.state, false)
        if (action is ViewAction.Open && library.entries().none { it.id == action.documentId && !it.isFolder && !it.trashed }) {
            return ViewUpdate(views.state, false)
        }
        val current = session()
        val status = current?.let { app.latestSaveStatus(it) }
        if (views.state.active?.documentId == current?.document?.value) {
            views.dispatch(ViewAction.Dirty(status?.hasUnsavedChanges == true))
        }
        if (action is ViewAction.Close && views.state.active?.dirty == true) {
            current?.let { app.trySend(AppCommand.BackgroundFlush(it)) }
            return ViewUpdate(views.state, false)
        }
        current?.let { app.latestViewport(it) }?.let { viewport ->
            if (views.state.active?.documentId == current.document.value) views.dispatch(ViewAction.Camera(viewport.camera))
        }
        val before = views.state
        val changesSession = action is ViewAction.Open || action is ViewAction.Switch || action is ViewAction.Close || action == ViewAction.Reopen
        if (changesSession && status?.hasUnsavedChanges == true) {
            current?.let { app.trySend(AppCommand.BackgroundFlush(it)) }
            return ViewUpdate(views.state, false)
        }
        val update = views.dispatch(action)
        if (!update.accepted) return update
        val planned = update.state.active
        if (action is ViewAction.Open && planned != null) {
            val openedEntry = library.entries().find { it.id == planned.documentId && it.lastOpened == 0L }
            if (openedEntry != null) views.dispatch(ViewAction.Settings(planned.settings.copy(horizontalScroll = openedEntry.defaults.horizontalScroll)))
        }
        val next = views.state.active
        if ((action is ViewAction.Open || action is ViewAction.Switch || action == ViewAction.Reopen) &&
            (next == null || library.entries().none { it.id == next.documentId && !it.isFolder && !it.trashed })) {
            views = Workspace(before)
            return ViewUpdate(before, false)
        }
        fun rollback(): ViewUpdate {
            views = Workspace(before)
            persistWorkspace()
            return ViewUpdate(before, false)
        }
        try {
            persistWorkspace()
        } catch (_: Exception) {
            views = Workspace(before)
            return ViewUpdate(before, false)
        }
        if (next != null && (before.activeTab != update.state.activeTab || action is ViewAction.Open || current?.document?.value != next.documentId)) {
            if (current?.document?.value != next.documentId) pendingOpen = next.documentId
            if (current != null && current.document.value != next.documentId) {
                pendingClose = current
                if (app.trySend(AppCommand.CloseSession(current)) is Outcome.Failure) {
                    pendingOpen = null
                    pendingClose = null
                    return rollback()
                }
            }
            val sent = if (current?.document?.value == next.documentId) Outcome.Success(inkspace.core.ports.RequestId(0))
                else {
                    library.entries().find { it.id == next.documentId }?.let { pendingDefaults[next.documentId] = it.defaults }
                    app.trySend(AppCommand.OpenSession(DocumentId(next.documentId), initialViewport = ViewportState(
                        session = SessionKey(DocumentId(next.documentId), SessionEpoch(0)),
                        camera = next.camera, isReadOnly = next.readOnly, isFocusMode = next.focus
                    )))
                }
            if (sent is Outcome.Failure) { pendingOpen = null; return rollback() }
            if (current?.document?.value != next.documentId) pendingClose = null
            library.submit(LibraryAction.Opened(next.documentId, System.currentTimeMillis()))
        }
        if (current != null && action is ViewAction.Camera) {
            val viewport = app.latestViewport(current)
            if (viewport != null && app.trySend(AppCommand.UpdateViewport(viewport.copy(camera = action.camera))) is Outcome.Failure) return rollback()
        }
        val commandId = update.commandId
        if (current != null && commandId != null) {
            if (commandId in setOf("pen", "fountain", "pencil", "brush", "highlighter", "eraser", "lasso", "shape", "ruler", "text", "image", "sticky", "tape")) {
                val applied = featureDispatch(current, NotebookAction.Tool(commandId, next?.settings ?: EditorSettings()))
                if (applied is Outcome.Failure) return rollback()
            }
            val command = when (commandId) {
                "undo" -> AppCommand.Undo(current)
                "redo" -> AppCommand.Redo(current)
                "readonly", "focus" -> app.latestViewport(current)?.let {
                    AppCommand.UpdateViewport(it.copy(isReadOnly = next?.readOnly ?: it.isReadOnly, isFocusMode = next?.focus ?: it.isFocusMode))
                }
                else -> null
            }
            if (command != null && app.trySend(command) is Outcome.Failure) return rollback()
        }
        if (action is ViewAction.Close && before.active?.id == action.tabId && current != null && next == null) {
            pendingClose = current
            if (app.trySend(AppCommand.CloseSession(current)) is Outcome.Failure) { pendingClose = null; return rollback() }
        }
        return update.copy(state = views.state)
    }
}
