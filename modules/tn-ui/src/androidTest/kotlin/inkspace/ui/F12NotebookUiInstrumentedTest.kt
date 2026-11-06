package inkspace.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import inkspace.core.error.FeatureError
import inkspace.core.id.DocumentId
import inkspace.core.model.DocumentState
import inkspace.core.outcome.Outcome
import inkspace.core.ports.RequestId
import inkspace.core.workspace.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class F12NotebookUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(gateway: UiGateway) {
        compose.setContent { MaterialTheme { NotebookShell(gateway, editor = {}) } }
        compose.waitForIdle()
    }

    @Test fun acceptance01to04and47_firstDocumentsCreatesConfiguredNotebook() {
        val gateway = UiGateway()
        screen(gateway)
        compose.onNodeWithText("documents").assertIsDisplayed()
        compose.onNodeWithText("+ New").performClick()
        compose.onNodeWithText("Notebook", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Create notebook").assertIsDisplayed()
        compose.onNodeWithText("A5").performClick()
        compose.onNodeWithText("blue").performScrollTo().performClick()
        compose.onNodeWithText("grid", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitUntil { gateway.created != null }
        assertEquals("grid", gateway.created!!.defaults.paper)
        assertEquals("blue", gateway.created!!.defaults.cover)
        assertEquals(419.53, gateway.created!!.defaults.widthPt, 0.01)
        compose.waitUntil { gateway.current.active != null }
        assertEquals("pen", gateway.current.active!!.tool)
    }

    @Test fun acceptance05and06_longPressMovesNotebookToFolder() {
        val gateway = UiGateway()
        screen(gateway)
        compose.onNodeWithText("Notebook · Notes").performTouchInput { longClick() }
        compose.onAllNodesWithText("Move", useUnmergedTree = true).onLast().performClick()
        compose.onNodeWithText("Folder", useUnmergedTree = true).performClick()
        compose.waitUntil { gateway.moved != null }
        assertEquals(setOf("notes"), gateway.moved!!.ids)
        assertEquals("folder", gateway.moved!!.parentId)
    }

    @Test fun acceptance07to10and15_tabsSwitchWithoutClosingAndUndoIsDirect() {
        val gateway = UiGateway()
        gateway.current = WorkspaceState(listOf(TabState("tab-a", "notes"), TabState("tab-b", "other")), "tab-a")
        screen(gateway)
        compose.onNodeWithText("● Notes").performClick()
        compose.waitUntil { gateway.commands.any { it is ViewAction.Switch } }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil { gateway.commands.any { it == ViewAction.Command("undo") } }
        assertEquals(2, gateway.current.tabs.size)
    }

    @Test fun acceptance11to14and16_toolbarSelectsToolsRepeatsSettingsAndShowsPages() {
        val gateway = UiGateway()
        gateway.current = WorkspaceState(listOf(TabState("tab-a", "notes")), "tab-a")
        screen(gateway)
        compose.onNodeWithText("● Notes").performClick()
        compose.onNodeWithText("Pen").performClick()
        compose.onNodeWithText("Notebook settings").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("notebook-tools").performScrollToNode(hasText("Eraser"))
        compose.onNodeWithText("Eraser").performClick()
        compose.waitUntil { gateway.commands.contains(ViewAction.Command("eraser")) }
        compose.onNodeWithTag("notebook-tools").performScrollToNode(hasText("Pages"))
        compose.onNodeWithText("Pages").performClick()
        compose.waitUntil { gateway.actions.any { it is NotebookAction.Settings } }
        compose.onNodeWithText("+ Page").assertExists()
    }

    @Test fun acceptance17and18_readOnlyAndFocusUseSharedCommands() {
        val gateway = UiGateway()
        gateway.current = WorkspaceState(listOf(TabState("tab-a", "notes")), "tab-a")
        screen(gateway)
        compose.onNodeWithText("● Notes").performClick()
        compose.onNodeWithTag("notebook-tools").performScrollToNode(hasText("Edit"))
        compose.onNodeWithText("Edit").performClick()
        compose.waitUntil { gateway.commands.contains(ViewAction.Command("readonly")) }
        compose.onNodeWithTag("notebook-tools").performScrollToNode(hasText("Focus"))
        compose.onNodeWithText("Focus").performClick()
        compose.waitUntil { gateway.current.active!!.focus }
        compose.onNodeWithText("Exit Focus").assertIsDisplayed()
        compose.onNodeWithText("Pages").assertDoesNotExist()
    }

    class UiGateway : NotebookGateway {
        var current by mutableStateOf(WorkspaceState())
        val commands = java.util.concurrent.CopyOnWriteArrayList<ViewAction>()
        val actions = java.util.concurrent.CopyOnWriteArrayList<NotebookAction>()
        @Volatile var created: LibraryAction.Create? = null
        @Volatile var moved: LibraryAction.Move? = null
        private val catalog = mutableListOf(LibraryEntry("notes", "Notes"), LibraryEntry("other", "Other"), LibraryEntry("folder", "Folder", isFolder = true))
        override fun entries(section: LibrarySection) = catalog.toList()
        override fun workspace() = current
        override fun resolveKey(key: String, ctrl: Boolean, shift: Boolean, textFocused: Boolean): CommandDescriptor? = null
        override fun submit(action: LibraryAction): Outcome<OperationId, LibraryError> {
            if (action is LibraryAction.Create) { created = action; catalog += LibraryEntry(action.id, action.name, defaults = action.defaults) }
            if (action is LibraryAction.Move) moved = action
            return Outcome.Success(OperationId("ui-operation"))
        }
        override fun dispatch(action: ViewAction): ViewUpdate {
            commands += action
            when (action) {
                is ViewAction.Open -> current = WorkspaceState(current.tabs + TabState(action.documentId, action.documentId), action.documentId)
                is ViewAction.Switch -> current = current.copy(activeTab = action.tabId)
                is ViewAction.Command -> current = current.copy(tabs = current.tabs.map { if (it.id != current.activeTab) it else when (action.id) { "focus" -> it.copy(focus = !it.focus); "readonly" -> it.copy(readOnly = !it.readOnly); else -> it.copy(tool = action.id) } })
                else -> Unit
            }
            return ViewUpdate(current, true)
        }
        override fun perform(action: NotebookAction): Outcome<RequestId, FeatureError> { actions += action; return Outcome.Success(RequestId(1)) }
        override fun editorState() = NotebookDocumentState(snapshot = DocumentState.createInitial(DocumentId("notes")).snapshot(), activePageId = "page-1", activeLayerId = "layer-1")
        override fun queryPdf(query: PdfQuery): Outcome<PdfViewData, FeatureError> = Outcome.Success(PdfViewData(text = "PDF text"))
        override fun thumbnail(documentId: String, maxEdge: Int): Outcome<NotebookThumbnail, FeatureError> = Outcome.Success(NotebookThumbnail(1, 1, intArrayOf(-1)))
        override fun copyFromTemplate(sourceId: String, id: String, name: String, defaults: NotebookDefaults): Outcome<OperationId, LibraryError> = submit(LibraryAction.Create(id, name, defaults = defaults))
    }
}
