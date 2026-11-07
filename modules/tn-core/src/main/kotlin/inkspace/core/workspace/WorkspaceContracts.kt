package inkspace.core.workspace

import inkspace.core.outcome.Outcome
import inkspace.core.unit.CameraTransform
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.LayerId
import inkspace.core.model.DocumentSnapshot
import inkspace.core.error.FeatureError
import inkspace.core.ports.RequestId
import kotlinx.serialization.Serializable

@Serializable
data class NotebookDefaults(
    val cover: String = "plain", val paper: String = "blank",
    val widthPt: Double = 595.28, val heightPt: Double = 841.89,
    val horizontalScroll: Boolean = false, val paperColor: Int = -1, val templatePage: Int = 1
) {
    init { require(widthPt.isFinite() && heightPt.isFinite() && widthPt > 0 && heightPt > 0 && templatePage > 0) }
}

@Serializable
data class LibraryEntry(
    val id: String, val name: String, val parentId: String? = null,
    val isFolder: Boolean = false, val favorite: Boolean = false,
    val trashed: Boolean = false, val lastOpened: Long = 0,
    val defaults: NotebookDefaults = NotebookDefaults(), val template: Boolean = false
)
enum class LibrarySection { DOCUMENTS, FAVORITES, RECENT, TEMPLATES, TRASH }
sealed interface LibraryAction {
    data class Import(val entries: List<LibraryEntry>) : LibraryAction
    data class Create(val id: String, val name: String, val parentId: String? = null,
        val isFolder: Boolean = false, val defaults: NotebookDefaults = NotebookDefaults()) : LibraryAction
    data class Rename(val id: String, val name: String) : LibraryAction
    data class Move(val ids: Set<String>, val parentId: String?) : LibraryAction
    data class Favorite(val ids: Set<String>, val value: Boolean) : LibraryAction
    data class Trash(val ids: Set<String>) : LibraryAction
    data class Restore(val ids: Set<String>) : LibraryAction
    data class Opened(val id: String, val timestamp: Long) : LibraryAction
    data class Template(val ids: Set<String>, val enabled: Boolean) : LibraryAction
    data class Defaults(val id: String, val defaults: NotebookDefaults) : LibraryAction
}
@Serializable
data class OperationId(val value: String)
data class LibraryError(val message: String)

@Serializable
data class TabState(
    val id: String, val documentId: String, val camera: CameraTransform = CameraTransform(),
    val tool: String = "pen", val readOnly: Boolean = false, val focus: Boolean = false,
    val dirty: Boolean = false, val selectedIds: Set<String> = emptySet(),
    val activePage: String? = null, val activeLayer: String? = null,
    val settings: EditorSettings = EditorSettings(), val zoom: ZoomViewState? = null
)
@Serializable
data class WorkspaceState(val tabs: List<TabState> = emptyList(), val activeTab: String? = null, val closedTabs: List<TabState> = emptyList(), val globalSettings: EditorSettings = EditorSettings()) {
    val active: TabState? get() = tabs.find { it.id == activeTab }
}
sealed interface ViewAction {
    data class Open(val documentId: String) : ViewAction
    data class Switch(val tabId: String) : ViewAction
    data class Close(val tabId: String) : ViewAction
    data class Reorder(val tabIds: List<String>) : ViewAction
    data class Dirty(val dirty: Boolean) : ViewAction
    data class Camera(val camera: CameraTransform) : ViewAction
    data class Command(val id: String) : ViewAction
    data class Selection(val ids: Set<String>) : ViewAction
    data class ActivePage(val id: String) : ViewAction
    data class ActiveLayer(val id: String) : ViewAction
    data class Settings(val settings: EditorSettings) : ViewAction
    data class GlobalSettings(val settings: EditorSettings) : ViewAction
    data class SetToolbarLayout(val layout: ToolbarLayout, val landscape: Boolean) : ViewAction
    data class OpenZoom(val state: ZoomViewState? = ZoomViewState()) : ViewAction
    data class MoveZoom(val region: inkspace.core.unit.DocRect) : ViewAction
    data class ZoomAdvance(val pageWidth: Double, val nextLine: Boolean = false) : ViewAction
    data class SetToolConfig(val tool: String, val config: ToolConfig) : ViewAction
    data class SetGestureConfig(val settings: inkspace.core.ink.GestureSettings) : ViewAction
    data object Reopen : ViewAction
}
data class ViewUpdate(val state: WorkspaceState, val accepted: Boolean, val commandId: String? = null)
data class CommandDescriptor(val id: String, val changesDocument: Boolean = false)
interface NotebookGateway {
    fun entries(section: LibrarySection): List<LibraryEntry>
    fun submit(action: LibraryAction): Outcome<OperationId, LibraryError>
    fun dispatch(action: ViewAction): ViewUpdate
    fun resolveKey(key: String, ctrl: Boolean, shift: Boolean, textFocused: Boolean): CommandDescriptor?
    fun workspace(): WorkspaceState
    fun perform(action: NotebookAction): Outcome<RequestId, FeatureError>
    fun editorState(): NotebookDocumentState
    fun thumbnail(documentId: String, maxEdge: Int = 128): Outcome<NotebookThumbnail, FeatureError>
    fun copyFromTemplate(sourceId: String, id: String, name: String, defaults: NotebookDefaults): Outcome<OperationId, LibraryError>
    fun queryPdf(query: PdfQuery): Outcome<PdfViewData, FeatureError>
    fun previewExport(options: ExportOptions): Outcome<String,FeatureError> = Outcome.Failure(FeatureError.InvalidParameters("Export preview unavailable"))
    fun takeError(): String? = null
    fun close() {}
}

@Serializable
data class EditorSettings(
    val horizontalScroll: Boolean = false, val textPinned: Boolean = false,
    val panel: String? = null, val panelDocked: Boolean = true,
    val penColor: Int = -16777216, val penWidthPt: Double = 2.0,
    val penOpacity: Float = 1f, val smoothing: Int = 1,
    val listScale: Float = 1f,
    val tools: Map<String, ToolConfig> = emptyMap(), val palettes: Map<String, Palette> = emptyMap(),
    val presets: List<ToolPreset> = emptyList(),
    val portraitToolbar: ToolbarLayout = ToolbarLayout(), val landscapeToolbar: ToolbarLayout = ToolbarLayout(),
    val gestures: inkspace.core.ink.GestureSettings = inkspace.core.ink.GestureSettings(),
    val inputPolicy: inkspace.core.ink.StylusInputPolicy = inkspace.core.ink.StylusInputPolicy()
) {
    fun configFor(tool: String): ToolConfig = tools[tool] ?: ToolConfig(color = penColor, widthPt = penWidthPt, opacity = penOpacity, smoothing = smoothing / 3f)
    init { require(presets.size <= 100 && tools.size <= 32 && palettes.size <= 32); require(penWidthPt.isFinite() && penWidthPt in 0.1..100.0 && penOpacity in 0f..1f && smoothing in 0..3 && listScale in 0.5f..2f) }
}

sealed interface NotebookAction {
    data class Tool(val id: String, val settings: EditorSettings = EditorSettings()) : NotebookAction
    data class Page(val command: String, val pageId: String? = null, val index: Int = -1,
        val widthPt: Double? = null, val heightPt: Double? = null, val policy: String = "KEEP_GEOMETRY",
        val defaults: NotebookDefaults? = null) : NotebookAction
    data class Layer(val command: String, val layerId: String? = null, val targetLayerId: String? = null,
        val name: String? = null, val visible: Boolean? = null, val locked: Boolean? = null,
        val opacity: Float? = null, val index: Int = -1) : NotebookAction
    data class Outline(val command: String, val id: String? = null, val parentId: String? = null,
        val title: String = "", val pageId: String? = null, val index: Int = -1) : NotebookAction
    data class Bookmark(val pageId: String, val enabled: Boolean) : NotebookAction
    data class SelectPage(val pageId: String) : NotebookAction
    data class SelectLayer(val layerId: String) : NotebookAction
    data class Selection(val command: String, val dx: Double = 0.0, val dy: Double = 0.0,
        val color: Int? = null, val widthPt: Double? = null) : NotebookAction
    data class InsertText(val text: String) : NotebookAction
    data class ImportResource(val uri: String, val mimeType: String, val asDocument: Boolean = false, val targetDocumentId: String? = null) : NotebookAction
    data class Settings(val settings: EditorSettings) : NotebookAction
}
data class NotebookOutline(val id: String, val title: String, val pageId: String, val parentId: String? = null, val depth: Int = 0)
data class NotebookDocumentState(
    val snapshot: DocumentSnapshot? = null, val activePageId: String? = null,
    val activeLayerId: String? = null, val selectedIds: Set<String> = emptySet(),
    val tool: String = "pen", val outline: List<NotebookOutline> = emptyList(),
    val bookmarks: Set<String> = emptySet(), val settings: EditorSettings = EditorSettings()
)
class NotebookThumbnail(val width: Int, val height: Int, pixels: IntArray) {
    private val pixels = pixels.copyOf()
    init { require(width in 1..2048 && height in 1..2048 && width.toLong() * height <= 2_000_000 && pixels.size == width * height) }
    fun copyPixels(): IntArray = pixels.copyOf()
    val byteSize: Int get() = pixels.size * 4
}

data class PdfQuery(
    val command: String, val objectId: String? = null, val query: String = "",
    val pageIndex: Int? = null, val selection: inkspace.core.unit.DocRect? = null, val startIndex: Int = 0
)
data class PdfViewData(
    val text: String = "", val spans: List<inkspace.core.pdf.PdfTextSpan> = emptyList(),
    val links: List<inkspace.core.pdf.PdfLink> = emptyList(),
    val outline: List<inkspace.core.pdf.PdfOutlineEntry> = emptyList(), val nextIndex: Int? = null
)
