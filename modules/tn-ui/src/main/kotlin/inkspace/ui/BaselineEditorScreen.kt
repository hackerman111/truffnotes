package inkspace.ui

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import inkspace.core.runtime.SaveStatusKind

@Composable
fun BaselineEditorScreen(
    state: BaselineUiState,
    onToggleReadOnly: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onDismissError: () -> Unit,
    onSurfaceCreated: (Int, Int) -> Unit,
    onSurfaceChanged: (Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onMotionEvent: ((MotionEvent) -> Boolean)? = null
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar hidden when in Focus Mode
            if (state.viewport?.isFocusMode != true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF3F4F6))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val saveLabel = when (state.saveStatus?.status) {
                        SaveStatusKind.SAVED -> "Saved (rev ${state.saveStatus.durableRevision.value})"
                        SaveStatusKind.SAVING -> "Saving... (${state.saveStatus.durableRevision.value}/${state.saveStatus.appliedRevision.value})"
                        SaveStatusKind.UNSAVED_ERROR -> "Save Error: ${state.saveStatus.lastError ?: "Unknown"}"
                        SaveStatusKind.PENDING_OVERFLOW -> "Pending Overflow: Input Paused"
                        null -> if (state.isLoading) "Loading..." else "Idle"
                    }
                    Text(
                        text = saveLabel,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onToggleReadOnly) {
                            Text(if (state.viewport?.isReadOnly == true) "Read-Only" else "Edit Mode")
                        }
                        Button(onClick = onToggleFocusMode) {
                            Text("Focus")
                        }
                    }
                }
            }

            if (state.isInputBlockedByOverflow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFEE2E2))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Input temporarily paused while pending edits are flushed to storage.",
                        color = Color(0xFF991B1B),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            val firstError = state.activeErrors.firstOrNull()
            if (firstError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFEF3C7))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${firstError.code}: ${firstError.message}",
                        color = Color(0xFF92400E),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onDismissError) {
                        Text("Dismiss")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(
                                object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        onSurfaceCreated(width.coerceAtLeast(1), height.coerceAtLeast(1))
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int
                                    ) {
                                        onSurfaceChanged(width.coerceAtLeast(1), height.coerceAtLeast(1))
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        onSurfaceDestroyed()
                                    }
                                }
                            )
                            if (onMotionEvent != null) {
                                setOnTouchListener { _, event ->
                                    onMotionEvent(event)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
