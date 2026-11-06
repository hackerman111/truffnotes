package inkspace.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import inkspace.core.unit.DocPoint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import inkspace.core.workspace.*
import inkspace.core.unit.CameraTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun NotebookShell(
    gateway: NotebookGateway,
    onImportImage: (Boolean) -> Unit = {},
    onImportPdf: (Boolean) -> Unit = {},
    textEditorFactory: ((android.content.Context, String?) -> android.view.View?)? = null,
    editor: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    var sectionName by rememberSaveable { mutableStateOf(LibrarySection.DOCUMENTS.name) }
    val section = LibrarySection.valueOf(sectionName)
    var entries by remember { mutableStateOf(emptyList<LibraryEntry>()) }
    var workspace by remember { mutableStateOf(gateway.workspace()) }
    var document by remember { mutableStateOf(NotebookDocumentState()) }
    var showLibrary by rememberSaveable { mutableStateOf(true) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var menuEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var defaults by remember { mutableStateOf(NotebookDefaults()) }
    var templateId by remember { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryMenu by remember { mutableStateOf(false) }
    var waitingClose by remember { mutableStateOf<String?>(null) }
    val active = workspace.active
    val settings = if (showLibrary) workspace.globalSettings else active?.settings ?: workspace.globalSettings

    suspend fun refreshEntries() {
        runCatching { withContext(Dispatchers.IO) { gateway.entries(section) } }
            .onSuccess { entries = it; workspace = gateway.workspace() }
            .onFailure { error = it.message ?: "Cannot read library" }
    }
    fun submit(action: LibraryAction) { scope.launch {
        runCatching { withContext(Dispatchers.IO) { gateway.submit(action) } }
            .onSuccess { error = it.errorOrNull()?.message; if (it.isSuccess) selected = emptySet() }
            .onFailure { error = it.message }
        refreshEntries()
    } }
    fun dispatch(action: ViewAction) { scope.launch {
        runCatching { withContext(Dispatchers.IO) { gateway.dispatch(action) } }
            .onSuccess { update ->
                workspace = update.state
                if (!update.accepted && action is ViewAction.Close) waitingClose = action.tabId
                error = if (update.accepted) null else if (action is ViewAction.Close) "Saving before closing…" else "Document is switching or saving. Try again shortly."
                if (update.accepted && workspace.active != null && (action is ViewAction.Open || action is ViewAction.Switch || action == ViewAction.Reopen || action is ViewAction.Close)) showLibrary = false
            }.onFailure { error = it.message ?: "Cannot update workspace" }
    } }
    fun perform(action: NotebookAction) { scope.launch {
        runCatching { withContext(Dispatchers.IO) { gateway.perform(action) } }
            .onSuccess { error = it.errorOrNull()?.toString(); document = gateway.editorState(); workspace = gateway.workspace() }
            .onFailure { error = it.message ?: "Cannot edit notebook" }
    } }
    fun openCreate(fromTemplate: String? = null) {
        templateId = fromTemplate
        val recent = entries.filter { !it.isFolder }.maxByOrNull { it.lastOpened }
        defaults = entries.find { it.id == fromTemplate }?.defaults ?: recent?.defaults ?: NotebookDefaults()
        name = if (fromTemplate == null) "Notebook" else "Copy of ${entries.find { it.id == fromTemplate }?.name ?: "template"}"
        dialog = "new-notebook"
    }
    BackHandler(enabled = !showLibrary || dialog != null || panel != null || selected.isNotEmpty()) {
        when {
            dialog != null -> dialog = null
            selected.isNotEmpty() -> selected = emptySet()
            panel != null -> { panel = null; perform(NotebookAction.Settings(settings.copy(panel = null))) }
            active?.focus == true -> dispatch(ViewAction.Command("focus"))
            else -> showLibrary = true
        }
    }
    LaunchedEffect(active?.id) { panel = active?.settings?.panel }
    LaunchedEffect(section) { selected = emptySet(); refreshEntries() }
    LaunchedEffect(gateway) {
        while (true) {
            val nextWorkspace = gateway.workspace()
            val importedTab = nextWorkspace.active?.takeIf { it.id != workspace.activeTab && entries.none { entry -> entry.id == it.documentId } }
            workspace = nextWorkspace
            if (importedTab != null) { refreshEntries(); showLibrary = false }
            document = gateway.editorState()
            gateway.takeError()?.let { error = it }
            delay(150)
        }
    }
    LaunchedEffect(waitingClose) {
        val id = waitingClose ?: return@LaunchedEffect
        while (true) {
            delay(250)
            val update = withContext(Dispatchers.IO) { gateway.dispatch(ViewAction.Close(id)) }
            workspace = update.state
            if (update.accepted || workspace.tabs.none { it.id == id }) {
                waitingClose = null
                error = null
                break
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || dialog != null) false else {
            val key = when (event.key) { Key.Z -> "z"; Key.Y -> "y"; Key.P -> "p"; Key.L -> "l"; Key.T -> "t"; Key.E -> "e"; else -> "" }
            val command = gateway.resolveKey(key, event.isCtrlPressed, event.isShiftPressed, false)
            if (command == null) false else {
                dispatch(ViewAction.Command(command.id))
                if (command.id == "text") { name = ""; dialog = "insert-text" }
                true
            }
        }
    }) {
        if (active?.focus != true || showLibrary) {
            TabStrip(workspace, entries, onLibrary = { showLibrary = true; scope.launch { refreshEntries() } }, onAction = ::dispatch)
        }
        error?.let { message -> Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp)) {
            Text(message, Modifier.weight(1f))
            TextButton(onClick = { error = null; waitingClose = null }) { Text("Dismiss") }
        } }
        if (showLibrary) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Box {
                    Button(onClick = { libraryMenu = true }) { Text("+ New") }
                    DropdownMenu(expanded = libraryMenu, onDismissRequest = { libraryMenu = false }) {
                        DropdownMenuItem(text = { Text("Notebook") }, onClick = { libraryMenu = false; openCreate() })
                        DropdownMenuItem(text = { Text("Folder") }, onClick = { libraryMenu = false; name = "Folder"; dialog = "new-folder" })
                        DropdownMenuItem(text = { Text("From Template") }, onClick = { libraryMenu = false; sectionName = LibrarySection.TEMPLATES.name })
                        DropdownMenuItem(text = { Text("Import PDF") }, onClick = { libraryMenu = false; onImportPdf(true) })
                        DropdownMenuItem(text = { Text("Import Image") }, onClick = { libraryMenu = false; onImportImage(true) })
                    }
                }
                TextButton(onClick = { grid = !grid }) { Text(if (grid) "List" else "Grid") }
                TextButton(onClick = { dialog = "settings" }) { Text("Settings") }
            }
            LazyRow(Modifier.fillMaxWidth()) { items(LibrarySection.entries) { value ->
                FilterChip(selected = section == value, onClick = { sectionName = value.name; parent = null }, label = { Text(value.name.lowercase()) }, modifier = Modifier.padding(4.dp))
            } }
            OutlinedTextField(query, { query = it }, label = { Text("Search names") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            if (parent != null) TextButton(onClick = { parent = entries.find { it.id == parent }?.parentId }) { Text("← Parent folder") }
            if (selected.isNotEmpty()) {
                LazyRow { item { Text("${selected.size} selected", Modifier.padding(12.dp)) }
                    item { TextButton(onClick = { selected = entries.filter { it.parentId == parent }.map { it.id }.toSet() }) { Text("Select all") } }
                    item { TextButton(onClick = { submit(LibraryAction.Favorite(selected, true)) }) { Text("Favorite") } }
                    item { TextButton(onClick = { dialog = "move" }) { Text("Move") } }
                    item { TextButton(onClick = { submit(if (section == LibrarySection.TRASH) LibraryAction.Restore(selected) else LibraryAction.Trash(selected)) }) { Text(if (section == LibrarySection.TRASH) "Restore" else "Trash") } }
                    item { TextButton(onClick = { selected = emptySet() }) { Text("Cancel") } }
                }
            }
            val visible = entries.filter { entry -> (section != LibrarySection.DOCUMENTS || entry.parentId == parent) && entry.name.contains(query, ignoreCase = true) }
            val onEntry: (LibraryEntry) -> Unit = { entry ->
                when {
                    selected.isNotEmpty() -> selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                    entry.trashed -> menuEntry = entry
                    entry.isFolder -> parent = entry.id
                    section == LibrarySection.TEMPLATES -> openCreate(entry.id)
                    else -> dispatch(ViewAction.Open(entry.id))
                }
            }
            val onLongEntry: (LibraryEntry) -> Unit = { entry -> selected = selected + entry.id; menuEntry = entry }
            if (grid) LazyVerticalGrid(columns = GridCells.Adaptive((200 * settings.listScale).dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                gridItems(visible, key = { it.id }) { entry -> LibraryTile(entry, gateway, entry.id in selected, settings.listScale, true, { onEntry(entry) }, { onLongEntry(entry) }) }
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                items(visible, key = { it.id }) { entry -> LibraryTile(entry, gateway, entry.id in selected, settings.listScale, false, { onEntry(entry) }, { onLongEntry(entry) }) }
            }
        } else {
            if (active?.focus != true) {
                NotebookToolbar(document, active, onCommand = ::dispatch, onPerform = ::perform,
                    onPanel = { panel = if (panel == it) null else it; perform(NotebookAction.Settings(settings.copy(panel = panel))) },
                    onText = { name = ""; dialog = "insert-text" }, onImage = { onImportImage(false) },
                    onSettings = { dialog = "settings" })
            } else TextButton(onClick = { dispatch(ViewAction.Command("focus")) }) { Text("Exit Focus") }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val dock = maxWidth >= 900.dp && settings.panelDocked
                Row(Modifier.fillMaxSize()) {
                    if (panel != null && dock && active?.focus != true) NotebookSidebar(panel!!, document, ::perform, { kind, id, old -> name = old; dialog = "$kind:$id" }, Modifier.width(280.dp).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        editor()
                        if (active?.tool == "text" && active.readOnly != true && textEditorFactory != null) {
                            val textId = document.selectedIds.firstOrNull { id ->
                                when (val payload = document.snapshot?.directory?.getObject(inkspace.core.id.ObjectId(id))?.payload) {
                                    is inkspace.core.payload.TextPayload, is inkspace.core.payload.StickyPayload -> true
                                    is inkspace.core.payload.ShapePayload -> payload.label != null
                                    else -> false
                                }
                            }
                            val textObject = textId?.let { document.snapshot?.directory?.getObject(inkspace.core.id.ObjectId(it)) }
                            key(active.id, textId) {
                                AndroidView(modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true), factory = { context ->
                                    textEditorFactory(context, textId) ?: android.view.View(context)
                                }, update = { view ->
                                    textObject?.let { obj ->
                                        val text = when (val payload = obj.payload) {
                                            is inkspace.core.payload.TextPayload -> payload
                                            is inkspace.core.payload.StickyPayload -> payload.richText
                                            is inkspace.core.payload.ShapePayload -> payload.label
                                            else -> null
                                        }
                                        val local = text?.containerBounds ?: obj.transform.invert()?.transformRect(obj.bounds) ?: obj.bounds
                                        val origin = obj.transform.transformPoint(DocPoint(local.left, local.top))
                                        val point = active.camera.docToView(origin)
                                        view.translationX = point.x.toFloat(); view.translationY = point.y.toFloat()
                                        view.rotation = Math.toDegrees(kotlin.math.atan2(obj.transform.m10, obj.transform.m00)).toFloat()
                                        view.pivotX = 0f; view.pivotY = 0f
                                        val width = (local.width * active.camera.scale).toInt().coerceIn(1, 8192)
                                        val height = (local.height * active.camera.scale).toInt().coerceIn(1, 8192)
                                        if (view.layoutParams?.width != width || view.layoutParams?.height != height) view.layoutParams = android.view.ViewGroup.LayoutParams(width, height)
                                    }
                                })
                            }
                        }
                    }
                }
                if (panel != null && !dock && active?.focus != true) Surface(Modifier.width(300.dp).fillMaxHeight().align(Alignment.CenterStart), tonalElevation = 4.dp, shadowElevation = 8.dp) {
                    Column { TextButton(onClick = { panel = null; perform(NotebookAction.Settings(settings.copy(panel = null))) }) { Text("Close panel") }; NotebookSidebar(panel!!, document, ::perform, { kind, id, old -> name = old; dialog = "$kind:$id" }, Modifier.fillMaxSize()) }
                }
            }
        }
    }

    menuEntry?.let { entry -> AlertDialog(onDismissRequest = { menuEntry = null }, title = { Text(entry.name) }, text = {
        Column {
            TextButton(onClick = { name = entry.name; dialog = "rename:${entry.id}"; menuEntry = null }) { Text("Rename") }
            TextButton(onClick = { selected = setOf(entry.id); dialog = "move"; menuEntry = null }) { Text("Move") }
            TextButton(onClick = { submit(LibraryAction.Favorite(setOf(entry.id), !entry.favorite)); menuEntry = null }) { Text(if (entry.favorite) "Remove favorite" else "Favorite") }
            if (!entry.isFolder) TextButton(onClick = { submit(LibraryAction.Template(setOf(entry.id), !entry.template)); menuEntry = null }) { Text(if (entry.template) "Remove from Templates" else "Save as Template") }
            TextButton(onClick = { submit(if (entry.trashed) LibraryAction.Restore(setOf(entry.id)) else LibraryAction.Trash(setOf(entry.id))); menuEntry = null }) { Text(if (entry.trashed) "Restore" else "Trash") }
        }
    }, confirmButton = { TextButton(onClick = { menuEntry = null }) { Text("Done") } }) }

    when {
        dialog == "move" -> {
            val destinations by produceState(emptyList<LibraryEntry>(), dialog) {
                value = withContext(Dispatchers.IO) { gateway.entries(LibrarySection.DOCUMENTS).filter { it.isFolder } }
            }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Move selected items") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { submit(LibraryAction.Move(selected, null)); dialog = null }) { Text("Documents root") }
                destinations.filter { !it.trashed && it.id !in selected }.forEach { destination ->
                    TextButton(onClick = { submit(LibraryAction.Move(selected, destination.id)); dialog = null }) { Text(destination.name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        }
        dialog == "settings" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Notebook settings") }, text = {
            EditorSettingsForm(settings) { next ->
                if (active != null && !showLibrary) perform(NotebookAction.Settings(next))
                else dispatch(ViewAction.GlobalSettings(next))
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } })
        dialog == "new-notebook" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Create notebook") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                NotebookDefaultsForm(defaults) { defaults = it }
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            val id = UUID.randomUUID().toString(); val title = name.trim(); val chosen = defaults; val source = templateId
            dialog = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (source != null) gateway.copyFromTemplate(source, id, title, chosen)
                    else gateway.submit(LibraryAction.Create(id, title, parent, defaults = chosen))
                }
                error = result.errorOrNull()?.message
                refreshEntries()
                if (result.isSuccess) dispatch(ViewAction.Open(id))
            }
        }) { Text("Create") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
        dialog != null -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(when { dialog == "insert-text" -> "Insert text"; dialog == "new-folder" -> "New folder"; dialog!!.startsWith("outline-add") -> "Outline title"; else -> "Rename" }) }, text = {
            OutlinedTextField(name, { name = it }, label = { Text(if (dialog == "insert-text") "Text" else "Name") }, singleLine = dialog != "insert-text")
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            val target = dialog!!; val value = name; dialog = null
            when {
                target == "insert-text" -> perform(NotebookAction.InsertText(value))
                target == "new-folder" -> submit(LibraryAction.Create(UUID.randomUUID().toString(), value.trim(), parent, isFolder = true))
                target.startsWith("rename:") -> submit(LibraryAction.Rename(target.substringAfter(':'), value.trim()))
                target.startsWith("layer-name:") -> perform(NotebookAction.Layer("style", target.substringAfter(':'), name = value.trim()))
                target.startsWith("outline-name:") -> perform(NotebookAction.Outline("rename", target.substringAfter(':'), title = value.trim()))
                target.startsWith("outline-add:") -> perform(NotebookAction.Outline("add", parentId = target.substringAfter(':').ifBlank { null }, title = value.trim(), pageId = document.activePageId))
            }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(entry: LibraryEntry, gateway: NotebookGateway, selected: Boolean, scale: Float, grid: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, entry.id, entry.lastOpened) {
        if (!entry.isFolder && !entry.trashed) value = withContext(Dispatchers.IO) {
            gateway.thumbnail(entry.id).getOrNull()?.let { image -> Bitmap.createBitmap(image.copyPixels(), image.width, image.height, Bitmap.Config.ARGB_8888).asImageBitmap() }
        }
    }
    Card(Modifier.fillMaxWidth().padding(4.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding((12 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
            thumbnail?.let { Image(it, "Preview of ${entry.name}", Modifier.size(if (grid) 72.dp else 40.dp).padding(end = 8.dp)) }
            Column(Modifier.weight(1f)) {
                Text((if (entry.isFolder) "Folder · " else "Notebook · ") + entry.name, style = MaterialTheme.typography.titleMedium)
                Text(if (entry.trashed) "Trash" else entry.defaults.paper + if (entry.favorite) " · Favorite" else "")
                if (entry.lastOpened > 0) Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(entry.lastOpened)), style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Text("✓")
        }
    }
}

@Composable
private fun TabStrip(workspace: WorkspaceState, entries: List<LibraryEntry>, onLibrary: () -> Unit, onAction: (ViewAction) -> Unit) {
    var overflow by remember { mutableStateOf(false) }
    LazyRow(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        item { TextButton(onClick = onLibrary) { Text("Library") } }
        items(workspace.tabs, key = { it.id }) { tab ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAction(ViewAction.Switch(tab.id)) }) { Text((if (workspace.activeTab == tab.id) "● " else "") + (entries.find { it.id == tab.documentId }?.name ?: tab.documentId.take(16)) + if (tab.dirty) " •" else "") }
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Close") }, onClick = { menu = false; onAction(ViewAction.Close(tab.id)) })
                    val index = workspace.tabs.indexOf(tab)
                    fun move(to: Int) { val ids = workspace.tabs.map { it.id }.toMutableList(); ids.removeAt(index); ids.add(to, tab.id); onAction(ViewAction.Reorder(ids)) }
                    if (index > 0) DropdownMenuItem(text = { Text("Move left") }, onClick = { menu = false; move(index - 1) })
                    if (index < workspace.tabs.lastIndex) DropdownMenuItem(text = { Text("Move right") }, onClick = { menu = false; move(index + 1) })
                }
            }
        }
        item { Box {
            TextButton(onClick = { overflow = true }) { Text("All tabs") }
            DropdownMenu(overflow, { overflow = false }) {
                workspace.tabs.forEach { tab -> DropdownMenuItem(text = { Text(entries.find { it.id == tab.documentId }?.name ?: tab.documentId) }, onClick = { overflow = false; onAction(ViewAction.Switch(tab.id)) }) }
                if (workspace.closedTabs.isNotEmpty()) DropdownMenuItem(text = { Text("Reopen closed tab") }, onClick = { overflow = false; onAction(ViewAction.Reopen) })
            }
        } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookToolbar(document: NotebookDocumentState, active: TabState?, onCommand: (ViewAction) -> Unit, onPerform: (NotebookAction) -> Unit, onPanel: (String) -> Unit, onText: () -> Unit, onImage: () -> Unit, onSettings: () -> Unit) {
    val tools = listOf("pen", "fountain", "pencil", "brush", "highlighter", "eraser", "lasso", "shape", "ruler", "text", "image", "sticky", "tape")
    LazyRow(Modifier.fillMaxWidth().testTag("notebook-tools")) {
        item { TextButton(onClick = { onCommand(ViewAction.Command("undo")) }) { Text("Undo") } }
        item { TextButton(onClick = { onCommand(ViewAction.Command("redo")) }) { Text("Redo") } }
        items(tools) { tool ->
            val selected = active?.tool == tool
            Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = Modifier.padding(4.dp).combinedClickable(onClick = {
                if (selected && tool != "text" && tool != "image") onSettings() else {
                    onCommand(ViewAction.Command(tool)); if (tool == "text") onText(); if (tool == "image") onImage()
                }
            }, onLongClick = onSettings)) { Text(tool.replaceFirstChar { it.uppercase() }, Modifier.padding(12.dp)) }
        }
        item { TextButton(onClick = { onCommand(ViewAction.Command("readonly")) }) { Text(if (active?.readOnly == true) "Read only" else "Edit") } }
        item { TextButton(onClick = { onCommand(ViewAction.Command("focus")) }) { Text("Focus") } }
        items(listOf("Pages", "Outline", "Bookmarks", "Layers")) { name -> TextButton(onClick = { onPanel(name) }) { Text(name) } }
        item { TextButton(onClick = onSettings) { Text("Settings") } }
    }
    if (document.selectedIds.isNotEmpty()) LazyRow {
        item { Text("${document.selectedIds.size} selected", Modifier.padding(12.dp)) }
        items(listOf("delete", "copy", "duplicate", "group", "ungroup", "front", "back", "lock")) { command -> TextButton(onClick = { onPerform(NotebookAction.Selection(command)) }) { Text(command) } }
        item { TextButton(onClick = { onPerform(NotebookAction.Selection("move", dx = 10.0)) }) { Text("Move →") } }
        item { TextButton(onClick = { onPerform(NotebookAction.Selection("color", color = active?.settings?.penColor)) }) { Text("Apply color") } }
        item { TextButton(onClick = { onPerform(NotebookAction.Selection("width", widthPt = active?.settings?.penWidthPt)) }) { Text("Apply width") } }
    }
}

@Composable
private fun NotebookSidebar(panel: String, document: NotebookDocumentState, perform: (NotebookAction) -> Unit, rename: (String, String, String) -> Unit, modifier: Modifier) {
    val snapshot = document.snapshot
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp)) {
        Text(panel, style = MaterialTheme.typography.titleLarge)
        when (panel) {
            "Pages" -> {
                TextButton(onClick = { perform(NotebookAction.Page("add")) }) { Text("+ Page") }
                LazyColumn {
                    items(snapshot?.pages ?: emptyList(), key = { it.id.value }) { page ->
                        var expanded by remember { mutableStateOf(false) }
                        Column {
                            TextButton(onClick = { perform(NotebookAction.SelectPage(page.id.value)) }) { Text("${if (document.activePageId == page.id.value) "● " else ""}Page ${(snapshot?.pages?.indexOf(page) ?: 0) + 1} · ${page.widthPt.toInt()}×${page.heightPt.toInt()}") }
                            Row { TextButton(onClick = { expanded = !expanded }) { Text("Actions") }; TextButton(onClick = { perform(NotebookAction.Bookmark(page.id.value, page.id.value !in document.bookmarks)) }) { Text(if (page.id.value in document.bookmarks) "★" else "☆") } }
                            if (expanded) {
                                listOf("duplicate", "clear", "delete", "rotate", "up", "down").forEach { command -> TextButton(onClick = { perform(NotebookAction.Page(command, page.id.value)) }) { Text(command) } }
                                listOf("KEEP_GEOMETRY", "SCALE_CONTENT", "ALLOW_OUTSIDE").forEach { policy -> TextButton(onClick = { perform(NotebookAction.Page("resize", page.id.value, widthPt = 419.53, heightPt = 595.28, policy = policy)) }) { Text("A5 · ${policy.lowercase()}") } }
                                listOf("blank", "ruled", "grid", "dots", "millimeter", "cornell").forEach { paper -> TextButton(onClick = { perform(NotebookAction.Page("background", page.id.value, defaults = NotebookDefaults(paper = paper, widthPt = page.widthPt, heightPt = page.heightPt))) }) { Text("Paper · $paper") } }
                            }
                        }
                    }
                }
            }
            "Layers" -> {
                TextButton(onClick = { perform(NotebookAction.Layer("add", name = "Layer")) }) { Text("+ Layer") }
                LazyColumn {
                    items(snapshot?.layers?.filter { document.activePageId == null || it.pageId.value == document.activePageId } ?: emptyList(), key = { it.id.value }) { layer ->
                        var expanded by remember { mutableStateOf(false) }
                        var opacity by remember(layer.opacity) { mutableStateOf(layer.opacity) }
                        Column {
                            TextButton(onClick = { perform(NotebookAction.SelectLayer(layer.id.value)) }) { Text("${if (document.activeLayerId == layer.id.value) "● " else ""}${layer.name}") }
                            Row { TextButton(onClick = { perform(NotebookAction.Layer("lock", layer.id.value, locked = !layer.isLocked)) }) { Text(if (layer.isLocked) "Unlock" else "Lock") }; TextButton(onClick = { perform(NotebookAction.Layer("style", layer.id.value, visible = !layer.isVisible)) }) { Text(if (layer.isVisible) "Hide" else "Show") }; TextButton(onClick = { expanded = !expanded }) { Text("⋮") } }
                            if (expanded) {
                                Text("Opacity ${(opacity * 100).toInt()}%")
                                Slider(opacity, { opacity = it }, onValueChangeFinished = { perform(NotebookAction.Layer("style", layer.id.value, opacity = opacity)) })
                                TextButton(onClick = { rename("layer-name", layer.id.value, layer.name) }) { Text("Rename") }
                                listOf("duplicate", "delete", "up", "down", "merge", "group", "move-selection").forEach { command -> TextButton(onClick = { perform(NotebookAction.Layer(command, layer.id.value)) }) { Text(command) } }
                            }
                        }
                    }
                }
            }
            "Outline" -> {
                TextButton(onClick = { rename("outline-add", "", "Section") }) { Text("+ Section") }
                LazyColumn { items(document.outline, key = { it.id }) { node ->
                    Column(Modifier.padding(start = (node.depth.coerceAtMost(20) * 8).dp)) {
                        TextButton(onClick = { perform(NotebookAction.SelectPage(node.pageId)) }) { Text(node.title) }
                        Row { TextButton(onClick = { rename("outline-name", node.id, node.title) }) { Text("Rename") }; TextButton(onClick = { rename("outline-add", node.id, "Subsection") }) { Text("+ Child") } }
                        Row { listOf("up", "down", "delete").forEach { command -> TextButton(onClick = { perform(NotebookAction.Outline(command, node.id)) }) { Text(command) } } }
                    }
                } }
            }
            "Bookmarks" -> LazyColumn { items(document.bookmarks.toList(), key = { it }) { page ->
                Row { TextButton(onClick = { perform(NotebookAction.SelectPage(page)) }) { Text("Page ${(snapshot?.pages?.indexOfFirst { it.id.value == page } ?: -1) + 1}") }; TextButton(onClick = { perform(NotebookAction.Bookmark(page, false)) }) { Text("Remove") } }
            } }
        }
    }
}

@Composable
private fun NotebookDefaultsForm(defaults: NotebookDefaults, onChange: (NotebookDefaults) -> Unit) {
    Text("Page format")
    Row { listOf("A4" to (595.28 to 841.89), "A5" to (419.53 to 595.28), "Letter" to (612.0 to 792.0)).forEach { (label, size) -> TextButton(onClick = { onChange(defaults.copy(widthPt = size.first, heightPt = size.second)) }) { Text(label) } } }
    var width by remember(defaults.widthPt) { mutableStateOf(defaults.widthPt.toString()) }
    var height by remember(defaults.heightPt) { mutableStateOf(defaults.heightPt.toString()) }
    OutlinedTextField(width, { value -> width = value; value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..100000.0 }?.let { onChange(defaults.copy(widthPt = it)) } }, label = { Text("Width (pt)") }, singleLine = true)
    OutlinedTextField(height, { value -> height = value; value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..100000.0 }?.let { onChange(defaults.copy(heightPt = it)) } }, label = { Text("Height (pt)") }, singleLine = true)
    TextButton(onClick = { onChange(defaults.copy(widthPt = defaults.heightPt, heightPt = defaults.widthPt)) }) { Text("Rotate orientation") }
    Text("Paper")
    listOf("blank", "ruled", "grid", "dots", "millimeter", "cornell").chunked(3).forEach { row -> Row { row.forEach { paper -> FilterChip(defaults.paper == paper, { onChange(defaults.copy(paper = paper)) }, label = { Text(paper) }, modifier = Modifier.padding(2.dp)) } } }
    Text("Paper color")
    Row { listOf(-1, 0xFFFFF8DC.toInt(), 0xFFE8F5E9.toInt(), 0xFFE3F2FD.toInt()).forEach { color -> TextButton(onClick = { onChange(defaults.copy(paperColor = color)) }) { Text("●", color = Color(color)) } } }
    Text("Cover")
    Row { listOf("plain", "blue", "red", "green").forEach { cover -> TextButton(onClick = { onChange(defaults.copy(cover = cover)) }) { Text(if (defaults.cover == cover) "✓ $cover" else cover) } } }
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(defaults.horizontalScroll, { onChange(defaults.copy(horizontalScroll = it)) }); Text("Horizontal page scroll") }
}

@Composable
private fun EditorSettingsForm(settings: EditorSettings, onChange: (EditorSettings) -> Unit) {
    var draft by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { draft = settings }
    Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.textPinned, { draft = draft.copy(textPinned = it); onChange(draft) }); Text("Pin Text Tool") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.horizontalScroll, { draft = draft.copy(horizontalScroll = it); onChange(draft) }); Text("Horizontal page scroll") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.panelDocked, { draft = draft.copy(panelDocked = it); onChange(draft) }); Text("Dock panel on wide screens") }
        Text("Pen width ${"%.1f".format(draft.penWidthPt)} pt")
        Slider(draft.penWidthPt.toFloat(), { draft = draft.copy(penWidthPt = it.toDouble()) }, valueRange = 0.1f..20f, onValueChangeFinished = { onChange(draft) })
        Text("Pen opacity ${(draft.penOpacity * 100).toInt()}%")
        Slider(draft.penOpacity, { draft = draft.copy(penOpacity = it) }, onValueChangeFinished = { onChange(draft) })
        Text("Smoothing ${draft.smoothing}")
        Slider(draft.smoothing.toFloat(), { draft = draft.copy(smoothing = it.toInt()) }, valueRange = 0f..3f, steps = 2, onValueChangeFinished = { onChange(draft) })
        Row { listOf(-16777216, 0xFF1565C0.toInt(), 0xFFD32F2F.toInt(), 0xFF2E7D32.toInt(), 0xFFF9A825.toInt()).forEach { color -> TextButton(onClick = { draft = draft.copy(penColor = color); onChange(draft) }) { Text("●", color = Color(color)) } } }
        Text("Library row / tile scale")
        Slider(draft.listScale, { draft = draft.copy(listScale = it) }, valueRange = 0.5f..2f, onValueChangeFinished = { onChange(draft) })
    }
}
