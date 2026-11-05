package inkspace.spike.model

/**
 * High-precision coordinates and point representations.
 */
data class DocPoint(val x: Double, val y: Double)

/**
 * Ingested stylus or touch sample.
 * Preserves raw input axes: position, pressure, tilt, and nanosecond timestamp.
 */
data class Sample(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val tilt: Float = 0.0f,
    val timeNanos: Long = 0L,
    val isPredicted: Boolean = false
)

/**
 * Ephemeral draft stroke during active touch gesture.
 */
data class StrokeDraft(
    val id: String,
    val pointerId: Int,
    val samples: MutableList<Sample> = mutableListOf()
)

/**
 * Committed immutable vector stroke added to the document.
 */
data class CommittedStroke(
    val id: String,
    val samples: List<Sample>,
    val color: Int = 0xFF1A1A1A.toInt(),
    val baseWidth: Float = 4.0f
)

/**
 * Monotonic generation epoch for Android Surface lifecycle.
 * Protects against rendering into destroyed surfaces or stale buffer handles.
 */
data class SurfaceEpoch(
    val generation: Long,
    val isValid: Boolean,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Platform-independent text paint plan for StaticLayout / Canvas / PdfDocument replay.
 */
data class TextPaintPlan(
    val text: CharSequence,
    val textSizePx: Float = 36.0f,
    val textColor: Int = 0xFF000000.toInt(),
    val maxWidthPx: Int = 1000,
    val isRtl: Boolean = false,
    val containsEmoji: Boolean = false
)

/**
 * Performance measurement snapshot.
 */
data class SpikeMetrics(
    val inputDispatchLatencyMs: Float = 0.0f,
    val canvasPostLatencyMs: Float = 0.0f,
    val frameDurationMs: Float = 0.0f,
    val activeStrokeCount: Int = 0,
    val committedStrokeCount: Int = 0,
    val pssMemoryMib: Long = 0L,
    val surfaceEpoch: Long = 0L
)
