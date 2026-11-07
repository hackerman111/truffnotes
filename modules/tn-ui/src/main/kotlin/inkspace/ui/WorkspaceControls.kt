package inkspace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import kotlin.math.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import inkspace.core.workspace.*
import kotlinx.coroutines.launch
import inkspace.core.unit.*
import inkspace.core.ink.*
import inkspace.core.objects.*

@Composable
internal fun ZoomRegionIndicator(zoom: ZoomViewState, camera: CameraTransform) {
    Canvas(Modifier.fillMaxSize()) {
        val origin = camera.docToView(DocPoint(zoom.region.left, zoom.region.top))
        drawRect(Color(0xff1976d2), Offset(origin.x.toFloat(), origin.y.toFloat()), Size((zoom.region.width * camera.scale).toFloat(), (zoom.region.height * camera.scale).toFloat()), style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun ZoomWindow(zoom: ZoomViewState, dispatch: (ViewAction) -> Unit, pageWidth: Double, content: @Composable (ZoomViewState) -> Unit) {
    Column {
        LazyRow {
            item { TextButton({ dispatch(ViewAction.OpenZoom(null)) }) { Text("Close zoom") } }
            item { TextButton({ dispatch(ViewAction.OpenZoom(zoom.copy(collapsed = !zoom.collapsed))) }) { Text(if (zoom.collapsed) "Expand" else "Collapse") } }
            item { TextButton({ dispatch(ViewAction.OpenZoom(zoom.copy(pinned = !zoom.pinned))) }) { Text(if (zoom.pinned) "Unpin" else "Pin") } }
            item { TextButton({ dispatch(ViewAction.ZoomAdvance(pageWidth)) }) { Text("Advance →") } }
            item { TextButton({ dispatch(ViewAction.ZoomAdvance(pageWidth, true)) }) { Text("Next line") } }
            item { Toggle("Auto advance", zoom.autoAdvance) { dispatch(ViewAction.OpenZoom(zoom.copy(autoAdvance = it))) } }
        }
        if (!zoom.collapsed) {
            LazyRow {
                items(listOf("←", "→", "↑", "↓")) { direction -> TextButton(enabled = !zoom.pinned, onClick = {
                    val dx = when(direction) { "←" -> -20.0; "→" -> 20.0; else -> 0.0 }; val dy = when(direction) { "↑" -> -20.0; "↓" -> 20.0; else -> 0.0 }
                    val r = zoom.region; dispatch(ViewAction.MoveZoom(DocRect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)))
                }) { Text(direction) } }
                item { TextButton({ val r = zoom.region; dispatch(ViewAction.OpenZoom(zoom.copy(region = DocRect(r.left, r.top, r.left + r.width * .8, r.top + r.height * .8), overlapPt = zoom.overlapPt.coerceAtMost(r.width * .4)))) }) { Text("Magnify +") } }
                item { TextButton({ val r = zoom.region; dispatch(ViewAction.MoveZoom(DocRect(r.left, r.top, r.left + r.width * 1.25, r.top + r.height * 1.25))) }) { Text("Magnify −") } }
            }
            Box(Modifier.fillMaxWidth().height(minOf(zoom.heightDp, (LocalConfiguration.current.screenHeightDp * .35).toInt()).dp)) { content(zoom) }
            Slider(zoom.heightDp.toFloat(), { dispatch(ViewAction.OpenZoom(zoom.copy(heightDp = it.toInt()))) }, valueRange = 120f..480f)
            Slider(zoom.lineHeightPt.toFloat(), { dispatch(ViewAction.OpenZoom(zoom.copy(lineHeightPt = it.toDouble()))) }, valueRange = 8f..120f)
        }
    }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row { Checkbox(checked, change, enabled = enabled); Text(label, Modifier.padding(top = 12.dp)) }
}

@Composable
internal fun F15Controls(active: TabState?, settings: EditorSettings, dispatch: (ViewAction) -> Unit, change: (EditorSettings) -> Unit, onImportPdf: () -> Unit, onImportNative: () -> Unit, onExport: (String) -> Unit, gateway: NotebookGateway, onPickColor: () -> Unit, timer: inkspace.core.workspace.AccessoryTimer, onPanelVisible: (Boolean) -> Unit) {
    var panel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(panel) { onPanelVisible(panel != null) }
    LazyRow {
        item { TextButton({ dispatch(ViewAction.OpenZoom(if (active?.zoom == null) ZoomViewState() else null)) }) { Text("Zoom") } }
        items(listOf("Tool styles", "Toolbar", "Gestures", "Accessories", "PDF reading", "Export")) { item -> TextButton({ panel = item }) { Text(item) } }
        item { TextButton(onImportPdf) { Text("Import PDF") } }
        item { TextButton(onImportNative) { Text("Import native") } }
        item { Text("PDF export preserves source vectors; unsupported annotation types use shared appearance", Modifier.padding(8.dp)) }
    }
    panel?.let { title -> AlertDialog(onDismissRequest = { panel = null }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            when(title) {
                "Tool styles" -> ToolStyles(active?.tool ?: "pen", settings, change) { panel = null; onPickColor() }
                "Toolbar" -> ToolbarSettings(settings, change)
                "Gestures" -> GestureControls(settings, change)
                "Accessories" -> AccessoriesPanel(timer)
                "PDF reading" -> PdfReading(gateway)
                "Export" -> ExportPanel(gateway) { onExport(it); panel=null }
            }
        }
    }, confirmButton = { TextButton({ panel = null }) { Text("Done") } }) }
}

@Composable
private fun ToolStyles(tool: String, settings: EditorSettings, change: (EditorSettings) -> Unit, onPickColor: () -> Unit) {
    val config = settings.configFor(tool)
    fun update(value: ToolConfig) = change(settings.copy(tools = settings.tools + (tool to value)))
    Text(tool)
    Text("Width ${config.widthPt.toInt()} pt")
    Slider(config.widthPt.toFloat(), { update(config.copy(widthPt = it.toDouble())) }, valueRange = .1f..100f)
    Text("Opacity"); Slider(config.opacity, { update(config.copy(opacity = it)) })
    Text("Smoothing"); Slider(config.smoothing, { update(config.copy(smoothing = it)) })
    Text("Pressure"); Slider(config.pressure, { update(config.copy(pressure = it)) }, valueRange = 0f..2f)
    Text("Tilt"); Slider(config.tilt, { update(config.copy(tilt = it)) }, valueRange = 0f..2f)
    Toggle("Auto deselect", config.autoDeselect) { update(config.copy(autoDeselect = it)) }
    StrokeEndCap.entries.forEach { value -> TextButton({ update(config.copy(endCap = value)) }) { Text("${if (config.endCap == value) "✓" else ""} $value") } }
    StrokeLinePattern.entries.forEach { value -> TextButton({ update(config.copy(pattern = value)) }) { Text("${if (config.pattern == value) "✓" else ""} $value") } }
    if (tool == "shape") inkspace.core.payload.ShapeType.entries.forEach { value -> TextButton({ update(config.copy(shapeType = value)) }) { Text(value.name) } }
    if (tool == "laser") Toggle("Trail (off for Dot)", config.laserTrail) { update(config.copy(laserTrail = it)) }
    if (tool == "eraser") {
        EraserMode.entries.forEach { value -> TextButton({ update(config.copy(eraser = config.eraser.copy(mode = value))) }) { Text(value.name) } }
        EraserLayerPolicy.entries.forEach { value -> TextButton({ update(config.copy(eraser = config.eraser.copy(layerPolicy = value))) }) { Text("Layers: $value") } }
        EraserShape.entries.forEach { value -> TextButton({ update(config.copy(eraser = config.eraser.copy(shape = value))) }) { Text(value.name) } }
        Text("Eraser radius"); Slider(config.eraser.radiusPt.toFloat(), { update(config.copy(eraser = config.eraser.copy(radiusPt = it.toDouble()))) }, valueRange = .1f..100f)
        ObjectKindFilter.entries.forEach { kind -> Toggle(kind.name, kind in config.eraser.allowedObjectKinds) { enabled -> update(config.copy(eraser = config.eraser.copy(allowedObjectKinds = if (enabled) config.eraser.allowedObjectKinds + kind else config.eraser.allowedObjectKinds - kind))) } }
    }
    var hue by remember(tool) { mutableFloatStateOf(0f) }
    var saturation by remember(tool) { mutableFloatStateOf(1f) }
    var brightness by remember(tool) { mutableFloatStateOf(1f) }
    TextButton(onPickColor) { Text("Pick document color (sRGB)") }
    ColorWheel(hue, saturation, brightness) { h, sat -> hue = h; saturation = sat; update(config.copy(color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))) }
    Text("sRGB hue / saturation / brightness")
    Slider(hue, { hue = it; update(config.copy(color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))) }, valueRange = 0f..360f)
    Slider(saturation, { saturation = it; update(config.copy(color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))) })
    Slider(brightness, { brightness = it; update(config.copy(color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))) })
    val palette = settings.palettes[tool] ?: Palette()
    Text("Tap a palette color; drag to reorder")
    PaletteStrip(palette, { update(config.copy(color = it)) }) { reordered -> change(settings.copy(palettes = settings.palettes + (tool to Palette(reordered)))) }
    var hex by remember(tool) { mutableStateOf("") }
    OutlinedTextField(hex, { hex = it.take(8) }, label = { Text("ARGB hex (sRGB)") })
    val color = hex.removePrefix("#").toLongOrNull(16)?.takeIf { hex.length == 6 || hex.length == 8 }?.let { if (hex.length == 6) (it or 0xff000000).toInt() else it.toInt() }
    TextButton(enabled = color != null, onClick = { color?.let { update(config.copy(color = it)) } }) { Text("Use color") }
    TextButton(enabled = color != null && palette.colors.size < 12, onClick = { color?.let { change(settings.copy(palettes = settings.palettes + (tool to Palette((palette.colors + it).distinct())))) } }) { Text("Add to $tool palette") }
    TextButton({ change(settings.copy(palettes = settings.palettes + (tool to Palette()))) }) { Text("Reset palette") }
    var preset by remember { mutableStateOf("") }
    OutlinedTextField(preset, { preset = it.take(100) }, label = { Text("Preset name") })
    TextButton(enabled = preset.isNotBlank() && settings.presets.size < 100, onClick = { change(settings.copy(presets = settings.presets + ToolPreset(preset, tool, config))) }) { Text("Save preset") }
    settings.presets.filter { it.tool == tool }.forEach { saved -> Row { TextButton({ update(saved.config) }) { Text(saved.name) }; TextButton({ change(settings.copy(presets = settings.presets - saved)) }) { Text("Delete") } } }
}

@Composable
private fun ToolbarSettings(settings: EditorSettings, change: (EditorSettings) -> Unit) {
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var editLandscape by remember { mutableStateOf(landscape) }
    val layout = if(editLandscape) settings.landscapeToolbar else settings.portraitToolbar
    fun update(next: ToolbarLayout) = change(if(editLandscape) settings.copy(landscapeToolbar = next) else settings.copy(portraitToolbar = next))
    Toggle("Landscape layout", editLandscape) { editLandscape = it }
    Toggle("Compact", layout.compact) { update(layout.copy(compact = it)) }
    Toggle("Bottom dock", layout.dockBottom) { update(layout.copy(dockBottom = it)) }
    Toggle("Undo at end", layout.undoAtEnd) { update(layout.copy(undoAtEnd = it)) }
    Toggle("Focus auto hide", layout.autoHide) { update(layout.copy(autoHide = it)) }
    Toggle("Focus on right", layout.focusSide == "right") { update(layout.copy(focusSide = if(it) "right" else "left")) }
    Text("Button size ${layout.sizeDp} dp"); Slider(layout.sizeDp.toFloat(), { update(layout.copy(sizeDp = it.toInt())) }, valueRange = 32f..80f)
    layout.tools.forEachIndexed { index, tool -> Row {
        TextButton(enabled = tool != "lasso", onClick = { update(layout.copy(hidden = if(tool in layout.hidden) layout.hidden - tool else layout.hidden + tool)) }) { Text("${if(tool in layout.hidden) "Show" else "Hide"} $tool") }
        TextButton(enabled = index > 0, onClick = { val tools = layout.tools.toMutableList(); tools.removeAt(index); tools.add(index - 1, tool); update(layout.copy(tools = tools)) }) { Text("↑") }
    } }
    TextButton({ update(ToolbarLayout()) }) { Text("Reset toolbar") }
}

@Composable
private fun GestureControls(settings: EditorSettings, change: (EditorSettings) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember(context) { context.getSystemService(android.content.Context.INPUT_SERVICE) as android.hardware.input.InputManager }
    var devices by remember { mutableStateOf(manager.inputDeviceIds.map { manager.getInputDevice(it) }.filterNotNull()) }
    DisposableEffect(manager) {
        val listener = object : android.hardware.input.InputManager.InputDeviceListener {
            fun refresh() { devices = manager.inputDeviceIds.map { manager.getInputDevice(it) }.filterNotNull() }
            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        manager.registerInputDeviceListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { manager.unregisterInputDeviceListener(listener) }
    }
    val styluses = devices.filter { it.supportsSource(android.view.InputDevice.SOURCE_STYLUS) || it.supportsSource(android.view.InputDevice.SOURCE_BLUETOOTH_STYLUS) }
    Text(if (styluses.isEmpty()) "No stylus is currently reported by Android. Eraser button actions become available when a stylus connects." else "Connected stylus: " + styluses.joinToString { it.name })
    styluses.forEach { device ->
        val axes = device.motionRanges.map { it.axis }
        Text("${device.name}: pressure ${if (android.view.MotionEvent.AXIS_PRESSURE in axes) "available" else "not reported"}, tilt ${if (android.view.MotionEvent.AXIS_TILT in axes) "available" else "not reported"}. Buttons work when Android delivers a stylus button event.")
    }
    val g = settings.gestures; val p = settings.inputPolicy
    fun gesture(next: GestureSettings) = change(settings.copy(gestures = next))
    Toggle("Geometric gestures", g.geometricGesturesEnabled) { gesture(g.copy(geometricGesturesEnabled = it)) }
    Toggle("Hold to shape", g.holdToShapeEnabled) { gesture(g.copy(holdToShapeEnabled = it)) }
    Toggle("Circle to lasso", g.circleToLassoEnabled) { gesture(g.copy(circleToLassoEnabled = it)) }
    Toggle("Scribble erase", g.scribbleEraseEnabled) { gesture(g.copy(scribbleEraseEnabled = it)) }
    Toggle("Two finger undo", g.twoFingerUndoEnabled) { gesture(g.copy(twoFingerUndoEnabled = it)) }
    Toggle("Three finger redo", g.threeFingerRedoEnabled) { gesture(g.copy(threeFingerRedoEnabled = it)) }
    Text("Hold delay ${g.holdThresholdMs} ms"); Slider(g.holdThresholdMs.toFloat(), { gesture(g.copy(holdThresholdMs = it.toLong())) }, valueRange = 150f..1500f)
    Text("Gesture distance ${g.multiTapMaxDistancePt} pt"); Slider(g.multiTapMaxDistancePt.toFloat(), { gesture(g.copy(multiTapMaxDistancePt = it.toDouble())) }, valueRange = 1f..100f)
    Toggle("Stylus only drawing", p.stylusOnlyDrawing) { change(settings.copy(inputPolicy = p.copy(stylusOnlyDrawing = it))) }
    Text("Palm posture ${p.palmPosture}")
    LazyRow { items(PalmPosture.entries) { posture -> TextButton({ change(settings.copy(inputPolicy = p.copy(palmPosture = posture))) }) { Text(posture.name) } } }
    Toggle("Palm rejection", p.palmRejectionEnabled) { change(settings.copy(inputPolicy = p.copy(palmRejectionEnabled = it))) }
    Toggle("Temporary eraser button", p.temporaryEraserButtonEnabled, enabled = styluses.isNotEmpty()) { change(settings.copy(inputPolicy = p.copy(temporaryEraserButtonEnabled = it))) }
    Text("Palm window ${p.palmRejectionWindowMs} ms"); Slider(p.palmRejectionWindowMs.toFloat(), { change(settings.copy(inputPolicy = p.copy(palmRejectionWindowMs = it.toLong()))) }, valueRange = 0f..1000f)
    Text("Circle tolerance ${g.circleReholdLineTolerancePt.toInt()} pt"); Slider(g.circleReholdLineTolerancePt.toFloat(), { gesture(g.copy(circleReholdLineTolerancePt = it.toDouble())) }, valueRange = 1f..100f)
    Text("Circle rehold ${g.circleReholdThresholdMs} ms"); Slider(g.circleReholdThresholdMs.toFloat(), { gesture(g.copy(circleReholdThresholdMs = it.toLong())) }, valueRange = 100f..1500f)
    Text("Circle window ${g.circleReholdWindowMs} ms"); Slider(g.circleReholdWindowMs.toFloat(), { gesture(g.copy(circleReholdWindowMs = it.toLong())) }, valueRange = 200f..3000f)
    Text("Scribble reversals ${g.scribbleMinReversals}"); Slider(g.scribbleMinReversals.toFloat(), { gesture(g.copy(scribbleMinReversals = it.toInt())) }, valueRange = 2f..12f, steps = 9)
    Text("Scribble angle ${g.scribbleAngleToleranceDeg.toInt()} degrees"); Slider(g.scribbleAngleToleranceDeg.toFloat(), { gesture(g.copy(scribbleAngleToleranceDeg = it.toDouble())) }, valueRange = 5f..90f)
    Text("Scribble envelope ${g.scribbleEnvelopeTolerancePt.toInt()} pt"); Slider(g.scribbleEnvelopeTolerancePt.toFloat(), { gesture(g.copy(scribbleEnvelopeTolerancePt = it.toDouble())) }, valueRange = 1f..100f)
    Text("Tap duration ${g.multiTapMaxDurationMs} ms"); Slider(g.multiTapMaxDurationMs.toFloat(), { gesture(g.copy(multiTapMaxDurationMs = it.toLong())) }, valueRange = 100f..1000f)
    TextButton({ change(settings.copy(gestures = GestureSettings(), inputPolicy = StylusInputPolicy())) }) { Text("Reset gestures and input") }
}

@Composable
internal fun rememberAccessoryTimer(factory: (TimerSnapshot) -> AccessoryTimer): inkspace.core.workspace.AccessoryTimer {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("accessory-timer", android.content.Context.MODE_PRIVATE) }
    val timer = remember(prefs) {
        fun decode(raw: String?): List<Long> = raw.orEmpty().split(',').mapNotNull(String::toLongOrNull)
        val history = prefs.getString("history", "").orEmpty().split(';').mapNotNull { row ->
            val values = decode(row); values.firstOrNull()?.let { TimerSession(it, values.drop(1)) }
        }
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        val initial = TimerSnapshot(prefs.getLong("elapsed", 0),
            prefs.getLong("started", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE || prefs.getInt("boot", -2) != boot },
            decode(prefs.getString("laps", "")), history)
        val delegate = factory(initial)
        object : AccessoryTimer by delegate {
            fun persist() {
                val value = delegate.snapshot()
                prefs.edit().putLong("elapsed", value.accumulatedNanos).putLong("started", value.startedNanos ?: Long.MIN_VALUE)
                    .putInt("boot", boot).putString("laps", value.laps.joinToString(","))
                    .putString("history", value.history.joinToString(";") { (listOf(it.elapsedNanos) + it.laps).joinToString(",") }).apply()
            }
            override fun start() { delegate.start(); persist() }
            override fun pause() { delegate.pause(); persist() }
            override fun reset() { delegate.reset(); persist() }
            override fun lap(): Long = delegate.lap().also { persist() }
        }
    }
    return timer
}

@Composable
private fun AccessoriesPanel(timer: inkspace.core.workspace.AccessoryTimer) {
    var elapsed by remember { mutableLongStateOf(timer.elapsed()) }
    var laps by remember { mutableStateOf(timer.snapshot().laps) }
    var history by remember { mutableStateOf(timer.snapshot().history) }
    val context=androidx.compose.ui.platform.LocalContext.current.applicationContext
    val prefs=remember(context) {context.getSharedPreferences("accessory-timer",android.content.Context.MODE_PRIVATE)}
    var minutes by remember { mutableStateOf(prefs.getString("countdown-minutes","5") ?: "5") }
    LaunchedEffect(timer) { while (true) { elapsed = timer.elapsed(); kotlinx.coroutines.delay(100) } }
    Text("Stopwatch ${elapsed / 1_000_000_000}s")
    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4);prefs.edit().putString("countdown-minutes",minutes).apply() }, label = { Text("Countdown minutes") })
    val remaining = ((minutes.toLongOrNull() ?: 0) * 60 - elapsed / 1_000_000_000).coerceAtLeast(0)
    Text(if (remaining == 0L && elapsed > 0) "Timer finished" else "Countdown ${remaining}s")
    Row {
        TextButton({ timer.start() }) { Text("Start") }
        TextButton({ timer.pause() }) { Text("Pause") }
        TextButton({ timer.reset(); laps = emptyList(); history = timer.snapshot().history }) { Text("Reset") }
        TextButton({ timer.lap(); laps = timer.snapshot().laps }) { Text("Lap") }
    }
    laps.forEachIndexed { index, value -> Text("Lap ${index + 1}: ${value / 1_000_000_000.0}s") }
    history.asReversed().forEachIndexed { index, session -> Text("Session ${history.size - index}: ${session.elapsedNanos / 1_000_000_000.0}s, ${session.laps.size} laps") }
    Text("Laser: select Laser in the tool bar. Its trail expires after 1.5 seconds and is never saved to the document.")
}

@Composable
private fun PdfReading(gateway: NotebookGateway) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(PdfViewData()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    fun request(command: String, continuation: Boolean = false) {
        scope.launch {
            val value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                gateway.queryPdf(PdfQuery(command, query = query, startIndex = if (continuation) result.nextIndex ?: 0 else 0))
            }
            value.getOrNull()?.let { data ->
                result = if (continuation) data.copy(text = result.text + data.text, spans = result.spans + data.spans) else data
                message = if (result.text.isBlank() && result.links.isEmpty() && result.outline.isEmpty()) "No results" else null
            } ?: run { message = value.errorOrNull().toString() }
        }
    }
    fun navigate(index: Int) {
        scope.launch {
            message = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val state = gateway.editorState(); val snapshot = state.snapshot
                val objects = snapshot?.directory?.allResidentObjects().orEmpty()
                val source = objects.firstOrNull { it.payload is inkspace.core.payload.PdfObjectPayload && snapshot?.getLayer(it.layer)?.pageId?.value == state.activePageId }
                    ?: objects.firstOrNull { it.payload is inkspace.core.payload.PdfObjectPayload }
                val asset = (source?.payload as? inkspace.core.payload.PdfObjectPayload)?.page?.asset
                val target = objects.firstOrNull { (it.payload as? inkspace.core.payload.PdfObjectPayload)?.page?.let { page -> page.asset == asset && page.pageIndex == index } == true }
                val page = target?.let { snapshot?.getLayer(it.layer)?.pageId?.value }
                if (page == null) "This PDF page is not present in the notebook" else gateway.perform(NotebookAction.SelectPage(page)).errorOrNull()?.toString()
            }
        }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Search PDF text") })
    Row { listOf("text", "search", "outline", "links").forEach { command -> TextButton({ request(command) }) { Text(command) } } }
    message?.let { Text(it) }
    androidx.compose.foundation.text.selection.SelectionContainer { Text(result.text) }
    if (result.nextIndex != null) TextButton({ request("text", true) }) { Text("Load more text") }
    result.spans.forEachIndexed { index, span -> TextButton({ scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gateway.queryPdf(PdfQuery("search-hit", query = query, startIndex = index)) } } }) { Text("Show hit ${index + 1}: ${span.text.take(80)}") } }
    result.outline.forEach { entry -> TextButton({ navigate(entry.pageIndex) }, Modifier.padding(start = (entry.depth.coerceIn(0, 20) * 8).dp)) { Text(entry.title) } }
    result.links.forEach { link ->
        if (link.targetPage != null) TextButton({ navigate(link.targetPage!!) }) { Text("Page ${link.targetPage!! + 1}") }
        link.uri?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("mailto:") }?.let { uri -> TextButton({ runCatching { uriHandler.openUri(uri) }.onFailure { message = "Unable to open this link" } }) { Text(uri) } }
    }
}

@Composable
private fun ColorWheel(hue: Float, saturation: Float, brightness: Float, change: (Float, Float) -> Unit) {
    val currentChange by rememberUpdatedState(change)
    fun sample(point: Offset, width: Int, height: Int) {
        val dx = point.x - width / 2f; val dy = point.y - height / 2f
        val radius = minOf(width, height) / 2f
        currentChange(((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f, (sqrt(dx * dx + dy * dy) / radius).coerceIn(0f, 1f))
    }
    Canvas(Modifier.size(180.dp).pointerInput(Unit) { detectTapGestures { sample(it, size.width, size.height) } }.pointerInput(Unit) {
        detectDragGestures(onDragStart = { sample(it, size.width, size.height) }) { event, _ -> event.consume(); sample(event.position, size.width, size.height) }
    }) {
        val colors = (0..12).map { step -> Color(android.graphics.Color.HSVToColor(floatArrayOf(step * 30f, 1f, brightness))) }
        drawCircle(brush = Brush.sweepGradient(colors))
        drawCircle(brush = Brush.radialGradient(listOf(Color(android.graphics.Color.HSVToColor(floatArrayOf(0f, 0f, brightness))), Color.Transparent), radius = size.minDimension / 2))
        val radius = size.minDimension / 2 * saturation
        val angle = hue / 180f * PI.toFloat()
        val location = center + Offset(cos(angle) * radius, sin(angle) * radius)
        drawCircle(Color.Black, 6.dp.toPx(), location, style = Stroke(2.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), location, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun PaletteStrip(palette: Palette, select: (Int) -> Unit, reorder: (List<Int>) -> Unit) {
    val colors by rememberUpdatedState(palette.colors)
    val currentSelect by rememberUpdatedState(select)
    val currentReorder by rememberUpdatedState(reorder)
    var dragIndex by remember { mutableIntStateOf(-1) }
    Canvas(Modifier.fillMaxWidth().height(48.dp).pointerInput(Unit) {
        detectTapGestures { point -> colors.getOrNull((point.x / (size.width.toFloat() / colors.size)).toInt())?.let(currentSelect) }
    }.pointerInput(Unit) {
        detectDragGestures(onDragStart = { point -> dragIndex = (point.x / (size.width.toFloat() / colors.size)).toInt().coerceIn(0, colors.lastIndex) }, onDragEnd = { dragIndex = -1 }, onDragCancel = { dragIndex = -1 }) { event, _ ->
            event.consume()
            val target = (event.position.x / (size.width.toFloat() / colors.size)).toInt().coerceIn(0, colors.lastIndex)
            if (dragIndex in colors.indices && target != dragIndex) {
                val next = colors.toMutableList(); val moved = next.removeAt(dragIndex); next.add(target, moved); dragIndex = target; currentReorder(next)
            }
        }
    }) {
        val cell = size.width / colors.size
        colors.forEachIndexed { index, value ->
            val location = Offset(cell * (index + .5f), size.height / 2)
            drawCircle(Color(value), minOf(cell / 2 - 2.dp.toPx(), 18.dp.toPx()), location)
            if (index == dragIndex) drawCircle(Color.Gray, minOf(cell / 2 - 1.dp.toPx(), 20.dp.toPx()), location, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun ExportPanel(gateway: NotebookGateway, export: (String) -> Unit) {
    val state = gateway.editorState()
    val snapshot = state.snapshot
    var mode by remember { mutableStateOf("Native") }
    var scope by remember { mutableStateOf("full") }
    var first by remember { mutableStateOf("1") }
    var last by remember { mutableStateOf((snapshot?.pages?.size ?: 1).toString()) }
    var rectangle by remember { mutableStateOf("0,0,200,200") }
    var chosen by remember { mutableStateOf(snapshot?.layers?.map { it.id.value }?.toSet().orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    var sticky by remember { mutableStateOf(true) }
    var tape by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var previewOptions by remember { mutableStateOf<ExportOptions?>(null) }
    val jobScope=rememberCoroutineScope()
    Text("Format")
    listOf("Native","VectorPdf","FlattenedPdf","StandardAnnotations","PNG","JPEG").forEach { value -> Row { RadioButton(mode==value,{mode=value});Text(value,Modifier.padding(top=12.dp)) } }
    Text("Scope")
    listOf("full","page","range","region","selection","template","objects").filter { mode=="Native" || it !in setOf("template","objects") }.forEach { value -> Row { RadioButton(scope==value,{scope=value});Text(value,Modifier.padding(top=12.dp)) } }
    if(scope in setOf("range","template")) OutlinedTextField(first,{first=it.filter(Char::isDigit)},label={Text("First page (1-based)")})
    if(scope=="range") OutlinedTextField(last,{last=it.filter(Char::isDigit)},label={Text("Last page")})
    if(scope=="region") OutlinedTextField(rectangle,{rectangle=it},label={Text("Region in points: left,top,right,bottom")})
    Text("Layers")
    snapshot?.layers?.forEach { layer -> Toggle(layer.name,layer.id.value in chosen) { checked -> chosen=if(checked) chosen+layer.id.value else chosen-layer.id.value } }
    Toggle("Visible layers only",visible) {visible=it}
    Toggle("Expand Sticky notes in output",sticky) {sticky=it}
    Toggle("Reveal Tape in output",tape) {tape=it}
    Text("Native region exports retain whole intersecting objects and their dependencies. Tape-covered data remains editable and is not redacted. Template export uses one selected page.")
    if(mode=="StandardAnnotations") Text("Ink, Sticky, rectangle and ellipse use standard annotations. Other types retain their shared appearance; the export report lists fallbacks.")
    error?.let { Text(it) }
    fun options(): ExportOptions {
            val region=if(scope=="region") rectangle.split(',').map {it.trim().toDouble()}.let { require(it.size==4);DocRect(it[0],it[1],it[2],it[3]) } else null
            val start=first.toIntOrNull() ?: 1
            val end=if(scope=="range") last.toInt() else start
            require(chosen.isNotEmpty()) {"Choose at least one layer"}
            require(scope !in setOf("selection","objects") || state.selectedIds.isNotEmpty()) {"Select objects first"}
            require(scope !in setOf("range","template") || end <= (snapshot?.pages?.size ?: 0)) {"Page range is outside this notebook"}
            return ExportOptions(mode=mode,scope=scope,firstPage=start,lastPage=end,region=region,layerIds=chosen.takeUnless {it==snapshot?.layers?.map {layer->layer.id.value}?.toSet()},objectIds=state.selectedIds,visibleOnly=visible,stickyExpanded=sticky,tapeRevealed=tape)
    }
    Button({ runCatching { options() }.onSuccess { value -> jobScope.launch {
        val result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gateway.previewExport(value) }
        preview=result.getOrNull();previewOptions=if(result.isSuccess)value else null;error=result.errorOrNull()?.toString()
    } }.onFailure {error=it.message} }) {Text("Preview selection and fallbacks")}
    preview?.let {Text(it)}
    Button({ runCatching {options()}.onSuccess {export(inkspace.core.codec.CoreCodec.encodeToJson(it))}.onFailure {error=it.message} },enabled=preview!=null && runCatching {options()==previewOptions}.getOrDefault(false)) {Text("Choose output")}

}
