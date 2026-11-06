package inkspace.app

import android.os.Bundle
import android.net.Uri
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
    private var cameraUri: Uri? = null
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
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val asDocument = importAsDocument
        lifecycleScope.launch {
            try {
                val target = container ?: return@launch
                withTimeout(5000) { while (target.controller.uiState.activeSession == null) delay(16) }
                val result = withContext(Dispatchers.IO) { target.notebooks.perform(NotebookAction.ImportResource(uri.toString(), mimeType, asDocument)) }
                result.errorOrNull()?.let { Toast.makeText(this@MainActivity, it.toString(), Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun takePhoto(asDocument: Boolean) {
        importAsDocument = asDocument
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
        cameraUri = savedInstanceState?.getString(KEY_CAMERA_URI)?.let(Uri::parse)

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
                        NotebookShell(currentContainer.notebooks,
                            onImportImage = { asDocument -> importAsDocument = asDocument; imagePicker.launch(arrayOf("image/*")) },
                            onImportPdf = { asDocument -> importAsDocument = asDocument; pdfPicker.launch(arrayOf("application/pdf")) },
                            onCamera = ::takePhoto,
                            onClipboardImage = ::pasteImage,
                            textEditorFactory = currentContainer::createTextEditorView
                        ) {
                            val surfaceSession = uiState.activeSession
                            key(surfaceSession) {
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
                                    currentContainer.submitMotionEvent(event).isSuccess
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
