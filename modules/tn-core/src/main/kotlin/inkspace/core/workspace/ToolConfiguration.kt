package inkspace.core.workspace

import inkspace.core.ink.*
import inkspace.core.objects.EraserSpec
import inkspace.core.unit.*
import kotlinx.serialization.Serializable

val NOTEBOOK_TOOLS = listOf("pen", "fountain", "pencil", "brush", "highlighter", "eraser", "lasso", "shape", "ruler", "text", "image", "sticky", "tape", "laser")

@Serializable
data class Palette(val colors: List<Int> = listOf(0xff000000.toInt(), 0xffd32f2f.toInt(), 0xff1976d2.toInt())) {
    init { require(colors.size in 1..12) }
}
@Serializable
data class ToolConfig(
    val color: Int = 0xff000000.toInt(), val widthPt: Double = 2.0, val opacity: Float = 1f,
    val smoothing: Float = .5f, val pressure: Float = .6f, val tilt: Float = .3f,
    val endCap: StrokeEndCap = StrokeEndCap.ROUND, val pattern: StrokeLinePattern = StrokeLinePattern.SOLID,
    val eraser: EraserSpec = EraserSpec(), val autoDeselect: Boolean = true, val laserTrail: Boolean = true,
    val shapeType: inkspace.core.payload.ShapeType = inkspace.core.payload.ShapeType.Rectangle
) {
    init { require(widthPt.isFinite() && widthPt in .1..100.0); require(opacity in 0f..1f && smoothing in 0f..1f && pressure in 0f..2f && tilt in 0f..2f) }
    fun brush(tool: String) = BrushSpec(kind = when(tool) {
        "fountain" -> BrushKind.FOUNTAIN; "pencil" -> BrushKind.PENCIL; "brush" -> BrushKind.BRUSH
        "highlighter" -> BrushKind.HIGHLIGHTER; else -> BrushKind.BALL
    }, colorArgb = color, baseWidthPt = widthPt, opacity = opacity, smoothingStrength = smoothing,
        pressureSensitivity = pressure, tiltSensitivity = tilt, endCap = endCap, linePattern = pattern)
}
@Serializable
data class ToolPreset(val name: String, val tool: String, val config: ToolConfig) {
    init { require(name.isNotBlank() && name.length <= 100 && tool in NOTEBOOK_TOOLS) }
}
@Serializable
data class ToolbarLayout(
    val tools: List<String> = NOTEBOOK_TOOLS, val hidden: Set<String> = emptySet(),
    val compact: Boolean = false, val dockBottom: Boolean = false, val sizeDp: Int = 48,
    val undoAtEnd: Boolean = false, val focusSide: String = "left", val autoHide: Boolean = false
) {
    init { require(tools.size <= NOTEBOOK_TOOLS.size && tools.distinct().size == tools.size && tools.all { it in NOTEBOOK_TOOLS }); require(sizeDp in 32..80); require(focusSide in listOf("left", "right")) }
    fun visibleTools(): List<String> = (tools.filter { it !in hidden } + "lasso").distinct()
}
@Serializable
data class ZoomViewState(
    val region: DocRect = DocRect(0.0, 0.0, 200.0, 60.0), val lineStartPt: Double = 0.0,
    val lineHeightPt: Double = 24.0, val overlapPt: Double = 20.0,
    val pinned: Boolean = false, val collapsed: Boolean = false, val autoAdvance: Boolean = false,
    val heightDp: Int = 220
) {
    init { require(region.width > 0 && region.height > 0 && lineStartPt.isFinite() && lineHeightPt.isFinite() && lineHeightPt > 0 && overlapPt.isFinite() && overlapPt >= 0 && overlapPt < region.width); require(heightDp in 120..480) }
    fun camera(width: Int, height: Int): CameraTransform {
        val scale = minOf(width / region.width, height / region.height).coerceIn(MIN_CAMERA_SCALE, MAX_CAMERA_SCALE)
        return CameraTransform(scale, -region.left * scale, -region.top * scale)
    }
    fun advance(pageWidth: Double, nextLine: Boolean = false): ZoomViewState {
        val x = region.right - overlapPt
        val moveLine = nextLine || x + region.width > pageWidth
        val left = if (moveLine) lineStartPt else x
        val top = if (moveLine) region.top + lineHeightPt else region.top
        return copy(region = DocRect(left, top, left + region.width, top + region.height))
    }
}
