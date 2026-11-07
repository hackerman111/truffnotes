### F15. Zoom Window, настройка инструментов и полный основной UI

**Depends:** F08/F10/F11/F12/F13/F14. **Requirements:** R04/R07/R08/R13/R14/R38/R39. **Owns:** workspace zoom/settings/commands; `tn-media` начальные laser/timer модули; Android toolbar/settings/zoom; ink/content только настройки через интегратора.

**API:** core ViewAction SetToolbarLayout/OpenZoom/MoveZoom/ZoomAdvance/SetToolConfig/SetGestureConfig; ZoomViewState/ToolPreset/Palette/CapabilityState. Zoom — тот же engine/document session, новый viewport.

- [ ] F15.1 Tests `zoom_coordinate_equivalence`, `zoom_advance_no_gap`, `zoom_pdf_rotation`, `toolbar_orientation`, `tool_return`, `palette_isolation`, `readonly_tape`, `gesture_reset`. Одинаковый path из обычного view/Zoom в Doc units отличается≤0.01pt.
- [ ] F15.2 Zoom box/window resize/zoom/move/indicator/pin/collapse, auto-advance right/next-line/line-height; переход не переносят незавершённый stroke между разными transforms. Overlay работает поверх Notebook/PDF и не создаёт copy page.
- [ ] F15.3 Toolbar reorder/hide/show/overflow/reset, sizes/compact/dock top-bottom, separate orientation settings, Focus float/auto-hide/sides и Undo/Redo position. Lasso остаётся доступным; приложение не требует копировать неизменяемость панели Goodnotes, если Inkspace её настраивает.
- [ ] F15.4 Tool style/thickness/smoothing/pressure/tilt/ends/pattern/eraser modes/filters/autodeselect; custom presets. Отдельные color palettes: initial3/max12, HEX/wheel/drag/reset; eyedropper читает документ/PDF/image color в явном color space, не экран другого приложения.
- [ ] F15.5 Gesture enable/timing/sensitivity, palm posture/preferences, finger draw и stylus buttons/capabilities; disabled hardware action объясняется capability. Начальный tn-media реализует Laser Dot/Trail с TTL, Timer/Stopwatch/laps/history по монотонному времени; это рабочие Accessories, не placeholders до F20. Tests `laser_ephemeral`/`timer_background` проверяют отсутствие document mutation и двойного таймера после recreate. Ни один setting не скрывает незавершённую обязательную функцию.

**Gate:** real stylus Zoom/PDF/toolbar suites PASS, no duplicate strokes/history/geometry; resize/dock сохраняет camera anchor и не перекрывает доступный canvas. Полный перечень R04/R14/R39 settings сохраняется/reload/reset без влияния палитры одного инструмента на другой.

