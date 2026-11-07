package inkspace.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import inkspace.core.workspace.ZoomViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ZoomCanvas(container: TruffnotesAppContainer, zoom: ZoomViewState) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    val session = container.resolveActiveSession()
    val scope = rememberCoroutineScope()
    DisposableEffect(session, zoom.region, size) {
        onDispose { container.cancelDrawing(session) }
    }
    LaunchedEffect(session, size, zoom) {
        if (session == null || size.width == 0 || size.height == 0) return@LaunchedEffect
        val width = minOf(size.width, 1600)
        val height = (size.height.toDouble() * width / size.width).toInt().coerceIn(1, 1000)
        while (true) {
            val frame = withContext(Dispatchers.IO) { container.runtime.renderPreview(session, zoom.camera(width, height), width, height) }
            if (frame != null) image = Bitmap.createBitmap(frame.copyPixels(), frame.width, frame.height, Bitmap.Config.ARGB_8888)
            delay(80)
        }
    }
    val currentImage by rememberUpdatedState(image)
    val currentZoom by rememberUpdatedState(zoom)
    val currentSession by rememberUpdatedState(session)
    AndroidView(modifier = Modifier.fillMaxSize().onSizeChanged { size = it }, factory = { context ->
        object : View(context) {
            var frozenZoom: ZoomViewState? = null
            var frozenMain: inkspace.core.unit.CameraTransform? = null
            override fun onDetachedFromWindow() {
                if (frozenMain != null) container.cancelDrawing(currentSession)
                super.onDetachedFromWindow()
            }
            override fun onDraw(canvas: Canvas) { currentImage?.let { canvas.drawBitmap(it, null, android.graphics.Rect(0, 0, width, height), null) } }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    frozenZoom = currentZoom
                    frozenMain = currentSession?.let(container.runtime::latestViewport)?.camera
                }
                val source = frozenZoom?.camera(width, height) ?: return true
                val target = frozenMain ?: return true
                val transformed = MotionEvent.obtain(event)
                try {
                    val scale = target.scale / source.scale
                    transformed.transform(Matrix().apply { setValues(floatArrayOf(scale.toFloat(), 0f, (target.translateX-source.translateX*scale).toFloat(), 0f, scale.toFloat(), (target.translateY-source.translateY*scale).toFloat(), 0f, 0f, 1f)) })
                    container.submitMotionEvent(transformed)
                } finally { transformed.recycle() }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val point = source.viewToDoc(inkspace.core.unit.ViewPoint(event.x.toDouble(), event.y.toDouble()))
                    if (currentZoom.autoAdvance && point.x >= currentZoom.region.right-currentZoom.region.width*.15) {
                        scope.launch { withContext(Dispatchers.IO) {
                            val state = container.notebooks.editorState()
                            val page = state.snapshot?.pages?.firstOrNull { it.id.value == state.activePageId }
                            container.notebooks.dispatch(inkspace.core.workspace.ViewAction.ZoomAdvance(page?.widthPt ?: 595.28))
                        } }
                    }
                    frozenMain = null; frozenZoom = null
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) { frozenMain = null; frozenZoom = null }
                return true
            }
        }
    }, update = { it.invalidate() })
}
