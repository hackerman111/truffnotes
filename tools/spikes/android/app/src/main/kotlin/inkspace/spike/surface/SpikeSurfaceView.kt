package inkspace.spike.surface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import inkspace.spike.model.CommittedStroke
import inkspace.spike.model.Sample
import inkspace.spike.model.SpikeMetrics
import inkspace.spike.model.StrokeDraft
import inkspace.spike.model.SurfaceEpoch
import inkspace.spike.ports.DefaultGeometryPort
import inkspace.spike.ports.DefaultRenderPort
import inkspace.spike.ports.GeometryPort
import inkspace.spike.ports.RenderPort
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SpikeSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val geometryPort: GeometryPort = DefaultGeometryPort()
    private val renderPort: RenderPort = DefaultRenderPort()

    // Dedicated single-threaded render executor
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spike-render-executor").apply { priority = Thread.MAX_PRIORITY }
    }

    private val epochCounter = AtomicLong(0L)
    val currentEpoch = AtomicReference(SurfaceEpoch(0L, false))

    // State
    private val committedStrokes = mutableListOf<CommittedStroke>()
    private var activeDraft: StrokeDraft? = null
    private val stateLock = Any()

    var onMetricsUpdated: ((SpikeMetrics) -> Unit)? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val nextGen = epochCounter.incrementAndGet()
        currentEpoch.set(SurfaceEpoch(nextGen, true, width, height))
        renderFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val nextGen = epochCounter.incrementAndGet()
        currentEpoch.set(SurfaceEpoch(nextGen, true, width, height))
        renderFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val nextGen = epochCounter.incrementAndGet()
        currentEpoch.set(SurfaceEpoch(nextGen, false, 0, 0))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val action = event.actionMasked

        // Check FLAG_CANCELED if available (API 33+)
        val isFlagCanceled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
        } else false

        if (action == MotionEvent.ACTION_CANCEL || isFlagCanceled) {
            synchronized(stateLock) {
                // Cancel draft cleanly: no committed stroke, no phantom lines
                activeDraft = null
            }
            renderFrame()
            return true
        }

        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                synchronized(stateLock) {
                    val draft = StrokeDraft(
                        id = "stroke_${System.currentTimeMillis()}_$pointerId",
                        pointerId = pointerId
                    )
                    draft.samples.add(extractSample(event, pointerIndex, System.currentTimeMillis() * 1_000_000L))
                    activeDraft = draft
                }
                renderFrame(startNanos)
            }

            MotionEvent.ACTION_MOVE -> {
                synchronized(stateLock) {
                    val draft = activeDraft
                    if (draft != null) {
                        // Ingest all historical samples in chronological order
                        val historySize = event.historySize
                        for (h in 0 until historySize) {
                            val histSample = Sample(
                                x = event.getHistoricalX(pointerIndex, h),
                                y = event.getHistoricalY(pointerIndex, h),
                                pressure = event.getHistoricalPressure(pointerIndex, h),
                                tilt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h)
                                } else 0.0f,
                                timeNanos = event.getHistoricalEventTime(h) * 1_000_000L
                            )
                            draft.samples.add(histSample)
                        }

                        // Current sample
                        draft.samples.add(extractSample(event, pointerIndex, event.eventTime * 1_000_000L))
                    }
                }
                renderFrame(startNanos)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                synchronized(stateLock) {
                    val draft = activeDraft
                    if (draft != null && draft.samples.isNotEmpty()) {
                        draft.samples.add(extractSample(event, pointerIndex, event.eventTime * 1_000_000L))
                        committedStrokes.add(
                            CommittedStroke(
                                id = draft.id,
                                samples = draft.samples.toList(),
                                color = Color.BLACK,
                                baseWidth = 4.0f
                            )
                        )
                    }
                    activeDraft = null
                }
                renderFrame(startNanos)
            }
        }
        return true
    }

    private fun extractSample(event: MotionEvent, pointerIndex: Int, timeNanos: Long): Sample {
        val tilt = try {
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        } catch (_: Exception) {
            0.0f
        }
        return Sample(
            x = event.getX(pointerIndex),
            y = event.getY(pointerIndex),
            pressure = event.getPressure(pointerIndex).coerceIn(0.01f, 2.0f),
            tilt = tilt,
            timeNanos = timeNanos
        )
    }

    fun clearCanvas() {
        synchronized(stateLock) {
            committedStrokes.clear()
            activeDraft = null
        }
        renderFrame()
    }

    fun getCommittedCount(): Int = synchronized(stateLock) { committedStrokes.size }

    private fun renderFrame(inputStartNanos: Long = 0L) {
        val epoch = currentEpoch.get()
        if (!epoch.isValid) return

        renderExecutor.execute {
            val checkEpoch = currentEpoch.get()
            if (!checkEpoch.isValid || checkEpoch.generation != epoch.generation) return@execute

            val canvas: Canvas? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
            } catch (e: Exception) {
                null
            }

            if (canvas != null) {
                val renderStartNanos = SystemClock.elapsedRealtimeNanos()
                val strokesCopy: List<CommittedStroke>
                val draftCopy: StrokeDraft?
                synchronized(stateLock) {
                    strokesCopy = committedStrokes.toList()
                    val d = activeDraft
                    draftCopy = if (d != null) StrokeDraft(d.id, d.pointerId, d.samples.toMutableList()) else null
                }
                try {
                    canvas.drawColor(Color.WHITE)
                    renderPort.drawScene(canvas, strokesCopy, draftCopy, geometryPort)
                } finally {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }

                val postEndNanos = SystemClock.elapsedRealtimeNanos()
                val frameDurationMs = (postEndNanos - renderStartNanos) / 1_000_000.0f
                val postLatencyMs = if (inputStartNanos > 0L) (postEndNanos - inputStartNanos) / 1_000_000.0f else frameDurationMs

                val pssKb = Debug.getPss()
                val metrics = SpikeMetrics(
                    inputDispatchLatencyMs = if (inputStartNanos > 0L) (renderStartNanos - inputStartNanos) / 1_000_000.0f else 0.0f,
                    canvasPostLatencyMs = postLatencyMs,
                    frameDurationMs = frameDurationMs,
                    activeStrokeCount = if (draftCopy != null) 1 else 0,
                    committedStrokeCount = strokesCopy.size,
                    pssMemoryMib = pssKb / 1024L,
                    surfaceEpoch = epoch.generation
                )

                post { onMetricsUpdated?.invoke(metrics) }
            }
        }
    }
}
