package inkspace.app

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import inkspace.core.runtime.AppCommand
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.MIN_CAMERA_SCALE
import inkspace.core.unit.MAX_CAMERA_SCALE

/** Keeps multi-touch navigation out of the persisted stroke stream. */
class CanvasInputRouter(context: Context, private val container: TruffnotesAppContainer) {
    private var navigating = false
    private var lastX = 0f
    private var lastY = 0f
    private var tapStarted = 0L
    private var tapFingers = 0
    private var tapValid = false
    private val tapOrigins = mutableMapOf<Int, Pair<Float, Float>>()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val session = container.resolveActiveSession() ?: return true
            val viewport = container.runtime.latestViewport(session) ?: return true
            tapValid = false
            val camera = viewport.camera
            val scale = (camera.scale * detector.scaleFactor).coerceIn(MIN_CAMERA_SCALE, MAX_CAMERA_SCALE)
            val factor = scale / camera.scale
            val next = CameraTransform(scale, detector.focusX - (detector.focusX - camera.translateX) * factor,
                detector.focusY - (detector.focusY - camera.translateY) * factor)
            container.runtime.trySend(AppCommand.UpdateViewport(viewport.copy(camera = next)))
            return true
        }
    })
    fun onMotionEvent(event: MotionEvent): Boolean {
        if ((0 until event.pointerCount).any { event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }) {
            container.submitMotionEvent(event)
            return true
        }
        val finger = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_FINGER
        if (!finger) { container.submitMotionEvent(event); return true }
        val session = container.resolveActiveSession() ?: return true
        val viewport = container.runtime.latestViewport(session) ?: return true
        val settings = container.notebooks.editorState().settings
        val policy = settings.inputPolicy
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            tapStarted = event.eventTime; tapFingers = 1; tapValid = true; tapOrigins.clear()
        }
        tapFingers = maxOf(tapFingers, event.pointerCount)
        for (index in 0 until event.pointerCount) {
            val origin = tapOrigins.getOrPut(event.getPointerId(index)) { event.getX(index) to event.getY(index) }
            val distance = kotlin.math.hypot((event.getX(index)-origin.first).toDouble(), (event.getY(index)-origin.second).toDouble()) / viewport.camera.scale
            if (distance > settings.gestures.multiTapMaxDistancePt) tapValid = false
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) tapValid = false
        if (event.actionMasked == MotionEvent.ACTION_UP && tapValid && event.eventTime-tapStarted <= settings.gestures.multiTapMaxDurationMs) {
            if (tapFingers == 2 && settings.gestures.twoFingerUndoEnabled) container.runtime.trySend(AppCommand.Undo(session))
            if (tapFingers == 3 && settings.gestures.threeFingerRedoEnabled) container.runtime.trySend(AppCommand.Redo(session))
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            navigating = policy.stylusOnlyDrawing
            lastX = event.x; lastY = event.y
        }
        if (event.pointerCount > 1 && !navigating) {
            val cancel = MotionEvent.obtain(event)
            try { cancel.action = MotionEvent.ACTION_CANCEL; container.submitMotionEvent(cancel) } finally { cancel.recycle() }
            navigating = true
        }
        scaleDetector.onTouchEvent(event)
        if (navigating) {
            val x = (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount
            val y = (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress) {
                val camera = viewport.camera
                container.runtime.trySend(AppCommand.UpdateViewport(viewport.copy(camera = camera.copy(translateX = camera.translateX+x-lastX, translateY = camera.translateY+y-lastY))))
            }
            lastX = x; lastY = y
        } else container.submitMotionEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) navigating = false
        return true
    }
}
