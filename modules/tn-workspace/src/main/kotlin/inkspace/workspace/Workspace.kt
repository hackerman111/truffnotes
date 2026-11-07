package inkspace.workspace

import inkspace.core.workspace.*
import java.util.UUID

object CommandRegistry {
    private val commands = listOf(
        CommandDescriptor("undo", true), CommandDescriptor("redo", true),
        CommandDescriptor("readonly"), CommandDescriptor("focus"),
        CommandDescriptor("pen"), CommandDescriptor("fountain"), CommandDescriptor("pencil"),
        CommandDescriptor("brush"), CommandDescriptor("highlighter"), CommandDescriptor("eraser"),
        CommandDescriptor("lasso"), CommandDescriptor("shape"), CommandDescriptor("ruler"),
        CommandDescriptor("text"), CommandDescriptor("image"), CommandDescriptor("sticky"), CommandDescriptor("tape"), CommandDescriptor("laser")
    ).associateBy { it.id }
    fun toolbar(id: String): CommandDescriptor? = commands[id]
    fun keyboard(key: String, ctrl: Boolean = false, shift: Boolean = false, textFocused: Boolean = false): CommandDescriptor? {
        if (textFocused) return null
        val id = when {
            ctrl && key.lowercase() == "z" -> if (shift) "redo" else "undo"
            ctrl && key.lowercase() == "y" -> "redo"
            !ctrl && key.lowercase() == "p" -> "pen"
            !ctrl && key.lowercase() == "l" -> "lasso"
            !ctrl && key.lowercase() == "e" -> "eraser"
            !ctrl && key.lowercase() == "t" -> "text"
            else -> return null
        }
        return commands[id]
    }
}

/** Cold tabs contain view metadata, never snapshots or resident objects. */
class Workspace(initial: WorkspaceState = WorkspaceState()) {
    @Volatile var state = initial
        private set

    @Synchronized fun dispatch(action: ViewAction): ViewUpdate {
        fun reject() = ViewUpdate(state, false)
        fun update(transform: (TabState) -> TabState) {
            state = state.copy(tabs = state.tabs.map { if (it.id == state.activeTab) transform(it) else it })
        }
        var commandId: String? = null
        when (action) {
            is ViewAction.Open -> {
                val existing = state.tabs.find { it.documentId == action.documentId }
                if (existing == null && state.tabs.size >= 1000) return reject()
                val tab = existing ?: TabState(UUID.randomUUID().toString(), action.documentId, settings = state.globalSettings)
                state = state.copy(tabs = if (existing == null) state.tabs + tab else state.tabs, activeTab = tab.id)
            }
            is ViewAction.Switch -> {
                if (state.tabs.none { it.id == action.tabId }) return reject()
                state = state.copy(activeTab = action.tabId)
            }
            is ViewAction.Close -> {
                val tab = state.tabs.find { it.id == action.tabId } ?: return reject()
                if (tab.dirty) return reject()
                val remaining = state.tabs.filterNot { it.id == tab.id }
                state = state.copy(tabs = remaining, activeTab = if (state.activeTab == tab.id) remaining.lastOrNull()?.id else state.activeTab,
                    closedTabs = (state.closedTabs + tab).takeLast(100))
            }
            is ViewAction.Reorder -> {
                if (action.tabIds.size != state.tabs.size || action.tabIds.toSet() != state.tabs.map { it.id }.toSet()) return reject()
                state = state.copy(tabs = action.tabIds.map { id -> state.tabs.first { it.id == id } })
            }
            is ViewAction.Dirty -> update { it.copy(dirty = action.dirty) }
            is ViewAction.Camera -> update { it.copy(camera = action.camera) }
            is ViewAction.Selection -> update { it.copy(selectedIds = action.ids.toSet()) }
            is ViewAction.ActivePage -> update { it.copy(activePage = action.id) }
            is ViewAction.ActiveLayer -> update { it.copy(activeLayer = action.id) }
            is ViewAction.Settings -> update { it.copy(settings = action.settings) }
            is ViewAction.GlobalSettings -> state = state.copy(globalSettings = action.settings)
            is ViewAction.Command -> {
                val command = CommandRegistry.toolbar(action.id) ?: return reject()
                val active = state.active ?: return reject()
                if (active.readOnly && command.changesDocument) return reject()
                commandId = command.id
                update { when (command.id) {
                    "readonly" -> it.copy(readOnly = !it.readOnly)
                    "focus" -> it.copy(focus = !it.focus)
                    "pen", "fountain", "pencil", "brush", "highlighter", "eraser", "lasso", "shape", "ruler", "text", "image", "sticky", "tape", "laser" -> it.copy(tool = command.id)
                    else -> it
                } }
            }
            is ViewAction.SetToolbarLayout -> update { it.copy(settings = if (action.landscape) it.settings.copy(landscapeToolbar = action.layout) else it.settings.copy(portraitToolbar = action.layout)) }
            is ViewAction.OpenZoom -> update { it.copy(zoom = action.state) }
            is ViewAction.MoveZoom -> update { it.copy(zoom = it.zoom?.copy(region = action.region)) }
            is ViewAction.ZoomAdvance -> update { it.copy(zoom = it.zoom?.advance(action.pageWidth, action.nextLine)) }
            is ViewAction.SetToolConfig -> {
                if (action.tool !in NOTEBOOK_TOOLS) return reject()
                update { it.copy(settings = it.settings.copy(tools = it.settings.tools + (action.tool to action.config))) }
            }
            is ViewAction.SetGestureConfig -> update { it.copy(settings = it.settings.copy(gestures = action.settings)) }
            ViewAction.Reopen -> {
                if (state.tabs.size >= 1000) return reject()
                val tab = state.closedTabs.lastOrNull() ?: return reject()
                state = state.copy(tabs = state.tabs + tab, activeTab = tab.id, closedTabs = state.closedTabs.dropLast(1))
            }
        }
        return ViewUpdate(state, true, commandId)
    }
}
