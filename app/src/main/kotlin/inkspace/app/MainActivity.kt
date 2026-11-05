package inkspace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import inkspace.core.id.DocumentId
import inkspace.platform.SavedSessionBundle
import inkspace.ui.BaselineEditorScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var container: TruffnotesAppContainer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? TruffnotesApplication
        val currentContainer = app?.appContainer
        container = currentContainer

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
                        BaselineEditorScreen(
                            state = uiState,
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
                                currentContainer.onSurfaceDestroyed()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
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
        private const val KEY_SAVED_SESSION_JSON = "inkspace.saved_session_json"
        private const val DEFAULT_DOCUMENT_ID = "default-notebook"
    }
}
