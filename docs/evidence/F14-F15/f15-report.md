# F15 implementation evidence

Implemented UI and initial media support:

- NotebookShell offers native import/export callbacks and composable Zoom rendering hook. The document viewport draws the Zoom region indicator. Zoom controls expose move, magnification, pin, collapse, resize, next-line/advance, line height and auto-advance toggle. Parent integration owns shared-session rendering, input cancellation and automatic advance on stroke completion.
- Toolbar uses persisted separate portrait/landscape layout, reorder, hide/show, hidden tools overflow, reset, compact/button sizing, top/bottom docking, Undo/Redo position and focus side. Lasso remains available. Focus uses a floating side toolbar with explicit show/hide and a four second auto-hide delay. Tool selection follows the runtime document tool, including return to lasso.
- Per-tool controls expose width/opacity/smoothing/pressure/tilt/caps/patterns, shape kind, eraser mode/shape/radius/object filters, auto-deselect, independent palette reset/add (max 12), HEX sRGB and HSV wheel/sliders, draggable palette ordering, saved named presets and deletion.
- Gesture/input controls expose geometry, shape hold, circle lasso, scribble, undo/redo toggles, hold delay, gesture distance, stylus-only drawing, palm rejection/window and eraser button preference with reset. All circle/scribble/tap timing and tolerance fields are exposed. Android input-device discovery updates live with source and pressure/tilt capabilities; absent stylus disables the hardware eraser preference and explains why.
- Accessories dialog contains monotonic stopwatch, configurable countdown, laps/history, pause/reset. Saveable timestamp snapshots retain elapsed time across recreation without starting duplicate workers; clock updates are display-only. Timer survives accessories dialog dismissal while workspace controls remain mounted.
- PDF reading dialog runs text/search/outline/links requests off Main and offers selectable result text with continuation, clickable PDF outline/internal links and external web/mail links. Import PDF is available inside editor. Native/PDF/PNG/JPEG exports call parent handlers.
- tn-media is a pure JVM module without document/core dependency. LaserOverlay stores bounded transient view-coordinate points with 1.5-second TTL; dot replaces prior points and trail retains them until expiry. TimerAccessory contains monotonic timestamps and no scheduler.

Verification:

- RED recorded in `/tmp/truffnotes-media-red.log`: tests referenced missing LaserOverlay and TimerAccessory before implementation.
- GREEN `:tn-media:test :tn-ui:compileDebugKotlin` in `/tmp/truffnotes-f15-ui-green.log`. `laser_ephemeral` verifies expiry and dot/trail semantics. `timer_background` verifies idempotent start, restore with advancing monotonic time, pause, laps and reset.
- Parent must run combined integration compilation/tests after wiring.

Remaining acceptance gaps (F15 gate is not claimed complete):

- Real stylus/Zoom/PDF/device toolbar and camera-anchor/overlap testing has not run.
- Eyedropper UI now invokes parent onPickColor callback after closing the tool dialog; parent owns sampling the document renderer in sRGB.
- Android reports stylus source/axes, but stylus button events still need actual hardware verification.
- Palm posture choice has no domain setting; palm rejection and time window are configurable.
- PDF search results are selectable text; per-hit viewport highlighting is not yet connected. Internal link/outline navigation requires the target PDF page to be present in the notebook.
- Countdown completion is displayed, with no background alarm/notification.
- Timer saved state is UI recreation state rather than durable process/reboot history.

## Shared shape/Canvas replay follow-up

`CanvasReplay` now consumes shared sampled `ShapeReplayGeometry` contours for every ShapeType (rectangle/rounded rectangle/square/circle/ellipse/line/arrow/triangle/polygon/polyline/Bezier curve/arc) and every arrowhead style. Hollow ellipses retain their transparent center, polygon fills follow vertices, and line/arrow objects no longer replay as rectangle borders. Local points transform through object affine and camera once for software, Android Canvas and diagnostic PDF path commands. New payload containerBounds preserves local primitive geometry under affine rotation; legacy payloads without it retain inverse-world-AABB fallback.

Android Canvas now draws shape fill/stroke, sticky backgrounds, tape including pattern marks, and image assets. Image EXIF/crop/polygon-mask sampling reuses the software sampling routine via a bounded intermediate raster (up to 2048 per axis); this does not introduce a second brush pipeline. PDF object raster tiles remain a separate existing path.

RED `/tmp/truffnotes-shapes-red.log` recorded failing `line_has_no_rectangle_border`, `ellipse_without_fill_has_transparent_center_and_full_width`, and `triangle_fill_respects_vertices_instead_of_bounding_box` tests. GREEN `/tmp/truffnotes-shapes-green.log`: entire `:tn-render:testDebugUnitTest` suite passed, including unchanged existing goldens and those regressions.

Limits: Android Canvas/PdfDocument pixels still require device validation. Diagnostic PDF command strings lack alpha resources, while Android Canvas PDF output receives alpha through Paint. Arc payloads with zero sweep and no explicit arc parameters use a semicircle fallback; this cannot reconstruct unspecified original arc geometry. Image intermediate raster resolution is bounded; export does not promise full original image resolution beyond that bound.

## Parent integration follow-up

ZoomCanvas maps MotionEvent through a frozen per-stroke Zoom camera into the same session/ink path, cancels on geometry/disposal and advances after stroke completion. Eyedropper samples the document renderer in sRGB, including PDF/image tiles. Runtime settings receive per-tool brush settings, eraser, shape type and Tape color/opacity/width; hardware eraser leaves the selected tool unchanged. Android router forwards mixed stylus streams, pans/pinches finger streams, and recognizes bounded two/three-finger Undo/Redo taps. Global cancellation clears all custom tool pointers.

Timer UI consumes a core interface implemented by tn-media through runtime injection. No dependency-graph exception was introduced. Monotonic state survives UI recreation; reboot/durable history and background notification remain outside this implementation.

Host regression coverage includes reopen/drawing, active-session routing, laser, temporary hardware eraser, tool-switch/global multi-pointer cancellation, Tape style, shape replay, PDF page boxes, Zoom math/settings isolation and timer state. Android hardware and instrumented coordinate/toolbar/palm/IME tests remain unexecuted.
