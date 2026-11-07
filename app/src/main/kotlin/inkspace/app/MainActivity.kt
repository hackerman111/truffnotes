package inkspace.app

import android.os.Bundle
import android.net.Uri
import android.view.MotionEvent
import android.content.Intent
import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import inkspace.core.workspace.NotebookAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import inkspace.core.id.DocumentId
import inkspace.platform.SavedSessionBundle
import inkspace.ui.BaselineEditorScreen
import inkspace.ui.NotebookShell
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var container: TruffnotesAppContainer? = null
    private var importAsDocument = true
    private var importTarget: inkspace.core.model.SessionKey? = null
    private var cameraUri: Uri? = null
    private var nativeAsCopy = true
    private var pickingColor = false
    private fun sampleColor(x: Float, y: Float) {
        val target = container ?: return
        val session = target.resolveActiveSession() ?: return
        val viewport = target.runtime.latestViewport(session) ?: return
        val point = viewport.camera.viewToDoc(inkspace.core.unit.ViewPoint(x.toDouble(), y.toDouble()))
        lifecycleScope.launch {
            try {
                val color = withContext(Dispatchers.IO) {
                    target.runtime.renderPreview(session, inkspace.core.unit.CameraTransform(1.0, -point.x, -point.y), 1, 1)?.copyPixels()?.firstOrNull()
                        ?: error("Document color is unavailable")
                }
                withContext(Dispatchers.IO) {
                    val state = target.notebooks.editorState()
                    target.notebooks.dispatch(inkspace.core.workspace.ViewAction.SetToolConfig(state.tool, state.settings.configFor(state.tool).copy(color = color)))
                }
            } catch (error: Exception) { Toast.makeText(this@MainActivity, error.cause?.message ?: error.message, Toast.LENGTH_LONG).show() }
        }
    }
    private var exportMode = "Native"
    private var exportScope = "full"
    private var exportOptions = inkspace.core.workspace.ExportOptions()
    private var exportSelected = emptySet<String>()
    private var exportDocuments = emptySet<String>()
    private val collectionPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) lifecycleScope.launch {
            try {
                val target = requireNotNull(container)
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { inkspace.runtime.AndroidCollectionExchange(applicationContext,target.runtime).importCollection(uri,target.notebooks) }
                Toast.makeText(this@MainActivity,"Collection imported",Toast.LENGTH_LONG).show()
            } catch(error: Exception) { Toast.makeText(this@MainActivity,error.cause?.message ?: error.message,Toast.LENGTH_LONG).show() }
        }
    }
    private var exportingSession: inkspace.core.model.SessionKey? = null
    private val nativePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val target = requireNotNull(container)
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    val exchange = inkspace.runtime.AndroidNativeExchange(applicationContext, File(target.runtime.config.storageRootPath), target.runtime::snapshotForThumbnail)
                    val imported = exchange.importNative(uri, if (nativeAsCopy) DocumentId(java.util.UUID.randomUUID().toString()) else null)
                    val id = imported.snapshot.version.document.value
                    exchange.publishPending(target.notebooks)
                    if (target.notebooks.entries(inkspace.core.workspace.LibrarySection.DOCUMENTS).none { it.id == id }) {
                        val result = target.notebooks.submit(inkspace.core.workspace.LibraryAction.Import(listOf(inkspace.core.workspace.LibraryEntry(id,"Imported notebook",template=imported.template))))
                        check(result.isSuccess) { result.errorOrNull().toString() }
                    }
                    if(imported.template) check(target.notebooks.submit(inkspace.core.workspace.LibraryAction.Template(setOf(id),true)).isSuccess)
                    check(target.notebooks.dispatch(inkspace.core.workspace.ViewAction.Open(id)).accepted) { "Notebook imported; open it from Library after saving" }
                }
            } catch (error: Exception) { Toast.makeText(this@MainActivity, error.cause?.message ?: error.message ?: "Native import failed", Toast.LENGTH_LONG).show() }
        }
    }
    private val exportPicker = registerForActivityResult(object : ActivityResultContracts.CreateDocument("application/octet-stream") {
        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
            type = when (exportMode) { "Native", "Collection" -> "application/octet-stream"; "PNG" -> "image/png"; "JPEG" -> "image/jpeg"; else -> "application/pdf" }
        }
    }) { uri ->
        if (uri != null) lifecycleScope.launch {
            val mode = exportMode
            val session = exportingSession
            try {
                val target = requireNotNull(container)
                val revision = withContext(Dispatchers.IO) {
                    if(mode == "Collection") {
                        inkspace.runtime.AndroidCollectionExchange(applicationContext,target.runtime).export(exportDocuments,uri,target.notebooks.entries(inkspace.core.workspace.LibrarySection.DOCUMENTS))
                        null
                    } else {
                        requireNotNull(session) { "Export session closed" }
                        val snapshot = requireNotNull(target.runtime.latestSnapshot(session)) { "Notebook closed" }
                        inkspace.runtime.AndroidDocumentExport.export(applicationContext,target.runtime,session,snapshot,uri,mode,exportScope,exportSelected,exportOptions)
                    }
                }
                Toast.makeText(this@MainActivity, revision?.let { "Exported revision ${it.revision}" } ?: "Collection exported",Toast.LENGTH_LONG).show()
                if(revision?.report?.isNotEmpty()==true) android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Export report").setMessage(revision.report.joinToString("\n\n")).setPositiveButton("OK",null).show()
            } catch (error: Exception) { Toast.makeText(this@MainActivity, error.cause?.message ?: error.message ?: "Export failed", Toast.LENGTH_LONG).show() }
        }
    }
    private fun export(mode: String) {
        exportOptions = if(mode.startsWith("{")) inkspace.core.codec.CoreCodec.decodeFromJson<inkspace.core.workspace.ExportOptions>(mode).getOrNull()!!
            else inkspace.core.workspace.ExportOptions(mode=mode.substringBefore(':'),scope=mode.substringAfter(':',"full"))
        exportScope = exportOptions.scope
        exportMode = exportOptions.mode
        exportSelected = container?.notebooks?.editorState()?.selectedIds.orEmpty()
        exportingSession = container?.resolveActiveSession()
        if(exportOptions.scope in setOf("page","region")) {
            val target=container
            val session=exportingSession
            if(target!=null && session!=null) {
                val snapshot=target.runtime.latestSnapshot(session)
                val active=target.runtime.latestViewport(session)?.activePageId
                val index=snapshot?.pages?.indexOfFirst {it.id==active}?.coerceAtLeast(0)?.plus(1) ?: 1
                exportOptions=exportOptions.copy(firstPage=index,lastPage=maxOf(index,exportOptions.lastPage))
            }
        }
        val extension = when (exportMode) { "Native" -> "inknote"; "PNG" -> "png"; "JPEG" -> "jpg"; else -> "pdf" }
        exportPicker.launch("Notebook-${System.currentTimeMillis()}.$extension")
    }
    private val cameraPicker = registerForActivityResult(object : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("Camera output", input)
        }
    }) { success ->
        if (success) importResource(cameraUri, "image/jpeg")
        else Toast.makeText(this, "Camera cancelled or permission denied", Toast.LENGTH_LONG).show()
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importResource(uri, "image/*") }
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importResource(uri, "application/pdf") }

    private fun importResource(uri: Uri?, mimeType: String) {
        if (uri == null) return
        val asDocument = importAsDocument
        val destination = importTarget ?: container?.resolveActiveSession()
        lifecycleScope.launch {
            try {
                // Immediately stage the content stream within Activity lifecycle while SAF permission is active
                val stagedFile = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "staged_imports").apply { mkdirs() }
                    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                    val ext = if (mimeType == "application/pdf") ".pdf" else ".img"
                    val file = File.createTempFile("import-", ext, dir)
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            val bytes = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                val count = input.read(bytes)
                                if (count < 0) break
                                if (count == 0) continue
                                total += count
                                require(total <= 268_435_456L) { "Import exceeds 256 MiB" }
                                output.write(bytes, 0, count)
                            }
                        }
                    } ?: throw java.io.IOException("Cannot open input stream for $uri")
                    file
                }
                val target = container ?: return@launch
                if (!asDocument) {
                    withTimeout(5000) { while (target.resolveActiveSession() == null) delay(16) }
                }
                val localUri = Uri.fromFile(stagedFile)
                val result = withContext(Dispatchers.IO) {
                    val action = NotebookAction.ImportResource(localUri.toString(), mimeType, asDocument)
                    if (asDocument) target.notebooks.perform(action)
                    else target.runtime.performNotebookAction(requireNotNull(destination) { "Original import target is closed" }, action)
                }
                result.errorOrNull()?.let { Toast.makeText(this@MainActivity, it.toString(), Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun takePhoto(asDocument: Boolean) {
        importAsDocument = asDocument
        importTarget = container?.resolveActiveSession()
        try {
            val directory = File(cacheDir, "camera").apply { mkdirs() }
            directory.listFiles()?.filter { it.name.endsWith(".jpg") && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
            val photo = File.createTempFile("capture-", ".jpg", directory)
            cameraUri = FileProvider.getUriForFile(this, "$packageName.camera", photo)
            cameraPicker.launch(cameraUri!!)
        } catch (error: Exception) { Toast.makeText(this, error.message ?: "Camera unavailable", Toast.LENGTH_LONG).show() }
    }

    private fun pasteImage(asDocument: Boolean) {
        importAsDocument = asDocument
        importTarget = container?.resolveActiveSession()
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            val uri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri == null) Toast.makeText(this, "Clipboard contains no image URI", Toast.LENGTH_LONG).show()
            else importResource(uri, contentResolver.getType(uri) ?: "image/*")
        } catch (error: Exception) { Toast.makeText(this, error.message ?: "Clipboard unavailable", Toast.LENGTH_LONG).show() }
    }

    private fun receive(source: Intent?) {
        if (source?.action !in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) return
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) source?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") source?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val target = uri ?: source?.data ?: return
        importAsDocument = true
        importResource(target, source?.type ?: contentResolver.getType(target) ?: "application/octet-stream")
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? TruffnotesApplication
        val currentContainer = app?.appContainer
        container = currentContainer
        if (currentContainer != null) lifecycleScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            runCatching { inkspace.runtime.AndroidCollectionExchange(applicationContext,currentContainer.runtime).publishPending(currentContainer.notebooks) }
            runCatching { inkspace.runtime.AndroidNativeExchange(applicationContext, File(currentContainer.runtime.config.storageRootPath), currentContainer.runtime::snapshotForThumbnail).publishPending(currentContainer.notebooks) }
        }
        importAsDocument = savedInstanceState?.getBoolean("import_as_document", true) ?: true
        savedInstanceState?.getString("import_document")?.let { doc -> importTarget = inkspace.core.model.SessionKey(DocumentId(doc), inkspace.core.id.SessionEpoch(savedInstanceState.getLong("import_epoch"))) }
        cameraUri = savedInstanceState?.getString(KEY_CAMERA_URI)?.let(Uri::parse)
        exportScope = savedInstanceState?.getString("export_scope") ?: "full"
        exportOptions = savedInstanceState?.getString("export_options")?.let { inkspace.core.codec.CoreCodec.decodeFromJson<inkspace.core.workspace.ExportOptions>(it).getOrNull() } ?: inkspace.core.workspace.ExportOptions()
        exportSelected = savedInstanceState?.getStringArrayList("export_selected")?.toSet().orEmpty()
        exportDocuments = savedInstanceState?.getStringArrayList("export_documents")?.toSet().orEmpty()
        exportMode = savedInstanceState?.getString("export_mode") ?: "Native"
        nativeAsCopy = savedInstanceState?.getBoolean("native_as_copy", true) ?: true
        savedInstanceState?.getString("export_document")?.let { doc ->
            exportingSession = inkspace.core.model.SessionKey(DocumentId(doc), inkspace.core.id.SessionEpoch(savedInstanceState.getLong("export_epoch")))
        }

        val restoredBundle = savedInstanceState
            ?.getString(KEY_SAVED_SESSION_JSON)
            ?.let { raw ->
                runCatching { SavedSessionBundle.fromJsonString(raw) }.getOrNull()
            }

        if (currentContainer != null && currentContainer.controller.uiState.activeSession == null) {
            currentContainer.openOrRestoreSession(
                defaultDocumentId = DocumentId(DEFAULT_DOCUMENT_ID),
                savedBundle = restoredBundle
            )
        }

        setContent {
            MaterialTheme {
                Surface {
                    if (currentContainer == null) {
                        Text("Runtime failed to initialize")
                    } else {
                        var uiState by remember { mutableStateOf(currentContainer.controller.uiState) }
                        LaunchedEffect(currentContainer) {
                            while (true) {
                                currentContainer.syncEvents()
                                uiState = currentContainer.controller.uiState
                                delay(16L)
                            }
                        }
                        NotebookShell(currentContainer.notebooks, timerFactory = inkspace.runtime.AndroidDocumentExport::timer,
                            onImportImage = { asDocument -> importAsDocument = asDocument
                                importTarget = container?.resolveActiveSession(); imagePicker.launch(arrayOf("image/*")) },
                            onImportPdf = { asDocument -> importAsDocument = asDocument
                                importTarget = container?.resolveActiveSession(); pdfPicker.launch(arrayOf("application/pdf")) },
                            onCamera = ::takePhoto,
                            onClipboardImage = ::pasteImage,
                            onImportNative = { asCopy -> nativeAsCopy = asCopy; nativePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                            onImportCollection = { collectionPicker.launch(arrayOf("application/zip","application/octet-stream","*/*")) },
                            onExportCollection = { ids -> exportMode="Collection"; exportDocuments=ids; exportPicker.launch("Library-${System.currentTimeMillis()}.inkcollection") },
                            onExport = ::export,
                            onPickColor = { pickingColor = true; Toast.makeText(this, "Tap a document color (sRGB)", Toast.LENGTH_SHORT).show() },
                            zoomContent = { zoom ->
                                ZoomCanvas(currentContainer, zoom)
                            },
                            textEditorFactory = currentContainer::createTextEditorView
                        ) {
                            val surfaceSession = uiState.activeSession
                            key(surfaceSession) {
                            val inputRouter = remember(currentContainer, surfaceSession) { CanvasInputRouter(this@MainActivity, currentContainer) }
                            BaselineEditorScreen(
                                state = uiState,
                                showChrome = false,
                                onSurfaceAvailable = currentContainer::bindDrawingSurface,
                                onToggleReadOnly = {
                                    currentContainer.controller.updateViewport { vp ->
                                        vp.copy(isReadOnly = !vp.isReadOnly)
                                    }
                                    uiState = currentContainer.controller.uiState
                                },
                                onToggleFocusMode = {
                                    currentContainer.controller.updateViewport { vp ->
                                        vp.copy(isFocusMode = !vp.isFocusMode)
                                    }
                                    uiState = currentContainer.controller.uiState
                                },
                                onDismissError = {
                                    val firstErr = uiState.activeErrors.firstOrNull()
                                    if (firstErr != null) {
                                        currentContainer.controller.dismissError(firstErr)
                                        uiState = currentContainer.controller.uiState
                                    }
                                },
                                onSurfaceCreated = { widthPx, heightPx ->
                                    currentContainer.onSurfaceAttached(widthPx, heightPx)
                                },
                                onSurfaceChanged = { widthPx, heightPx ->
                                    currentContainer.onSurfaceChanged(widthPx, heightPx)
                                },
                                onSurfaceDestroyed = {
                                    currentContainer.onSurfaceDestroyed(surfaceSession)
                                },
                                onMotionEvent = { event ->
                                    if (pickingColor) {
                                        if (event.actionMasked == MotionEvent.ACTION_UP) { pickingColor = false; sampleColor(event.x, event.y) }
                                        true
                                    } else inputRouter.onMotionEvent(event)
                                }
                            )
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) receive(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        exportingSession?.let { outState.putString("export_document", it.document.value); outState.putLong("export_epoch", it.epoch.value) }
        outState.putString("export_scope", exportScope)
        outState.putString("export_options",inkspace.core.codec.CoreCodec.encodeToJson(exportOptions))
        outState.putStringArrayList("export_selected",ArrayList(exportSelected))
        outState.putStringArrayList("export_documents",ArrayList(exportDocuments))
        outState.putString("export_mode", exportMode)
        outState.putBoolean("native_as_copy", nativeAsCopy)
        outState.putBoolean("import_as_document", importAsDocument)
        importTarget?.let { outState.putString("import_document", it.document.value); outState.putLong("import_epoch", it.epoch.value) }
        cameraUri?.let { outState.putString(KEY_CAMERA_URI, it.toString()) }
        val bundle = container?.onSaveInstanceState()
        if (bundle != null) {
            outState.putString(KEY_SAVED_SESSION_JSON, bundle.toJsonString())
        }
    }

    override fun onStop() {
        super.onStop()
        container?.onBackgroundFlush()
    }

    companion object {
        private const val KEY_CAMERA_URI = "inkspace.camera_uri"
        private const val KEY_SAVED_SESSION_JSON = "inkspace.saved_session_json"
        private const val DEFAULT_DOCUMENT_ID = "default-notebook"
    }
}
