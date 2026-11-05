# Phase F06 Evidence Report — Реальный рукописный ввод (`:tn-ink`, `:tn-platform`, `:tn-runtime`)

```text
Phase / task / Requirement IDs: F06 (F06.1..F06.5) / R06, R07, acceptance19, acceptance20, acceptance21, acceptance45, acceptance46 (contributes to R13, R31)
Revision or source fingerprint: git branch main (post-F05 commit e90a3b1)
Changed paths: modules/tn-core, modules/tn-ink, modules/tn-platform, modules/tn-runtime, fixtures/input/f06-replay-corpus.json, settings.gradle.kts, docs/contracts/core-api-dump.txt, docs/evidence/F06/*, requirements.json
Public API/schema changes: Added InputError in :tn-core (inkspace.core.error.Errors.kt); added InkContracts.kt (RealSample, PredictedSample, InputToolType, InputCapabilityReport, InputBatch, BrushKind, StrokeEndCap, StrokeLinePattern, BrushSpec, StylusInputPolicy, EffectiveInkToolMode, StagedChunkRef, SpooledStrokeChunk, StrokeSpoolSink, InputContext, InkOutput) in :tn-core; implemented :tn-ink (Smoothing.kt, Brush.kt, Geometry.kt, Gesture.kt, InputSession.kt, InkEngine.kt); extended :tn-platform (InputTransport.kt with DeviceAxisCapabilities, LeasedInkInputBatch, extractInkBatchFromMotionEvent); wired InkEngine and InkGeometryPort into :tn-runtime (Runtime.kt)
Commands and exit codes:
  ./gradlew apiDump -> 0
  ./gradlew ktlintCheck detekt -> 0
  ./gradlew jvmTestAll -> 0
  ./gradlew architectureCheck apiCheck requirementsCheck -> 0
  ./gradlew androidUnitTestAll lint assembleDebug -> 0
Device / API / firmware / ABI / GPU / build profile: Host JVM (Linux x86_64, OpenJDK 26 targeting JVM 17) + Android SDK 36 / minSdk 29 debug APK (arm64-v8a, x86_64)
Fixture IDs and hashes: fixtures/input/f06-replay-corpus.json (8 deterministic replay fixtures: historical_points, pointer_reuse, mixed_finger_pen, cancel_stream, flag_canceled, axes_missing, long_stroke, prediction_replaced) with seed 20261105
Tests: passed: 15 F06 test cases across 3 JVM/Android unit suites (F06ReplayFixturesAndBrushesTest: 5, F06MotionEventAdapterTest: 7, F06InkRuntimeIntegrationAndLatencyTest: 3) / failed: 0 / skipped: 0
Measurements: raw path: docs/evidence/F06/w1-ink-latency-raw.json; W1 (30 strokes x 64 samples over 100 pages x 100 strokes): main_callback_dispatch_ms p50=0.0047 ms, p95=0.0178 ms (budget <= 1.0 ms); engine_ingest_64_samples_ms p50=0.0439 ms, p95=0.0879 ms (budget <= 2.0 ms); input_to_canvas_post_w1_ms p50=2.3331 ms, p95=4.8395 ms (budget <= 8.0 ms); durable_commit_lag_w1_ms p50=0.7741 ms, p95=1.0532 ms (budget <= 200.0 ms); phantom_strokes_on_cancel=0; predicted_samples_saved_in_db_or_undo=0; draft_to_committed_pixel_mismatch_count=0
Remaining limitations / failing acceptance criteria: Physical Android tablet stylus latency validation pending hardware attachment; all host JVM & Android unit/lint/assemble gates PASS.
Status: verified
```

## 1. Summary of Deliverables

1. **Replay Harness, Distinct Real/Predicted Samples & Canceled Stream Isolation (`F06.1`):**
   - Defined `RealSample` and `PredictedSample` as distinct immutable value types in `:tn-core` (`InkContracts.kt`), ensuring predicted samples can never enter `EditPlan`, `DocumentStore`, or Undo/Redo history.
   - Created deterministic input corpus `fixtures/input/f06-replay-corpus.json` covering `historical_points`, `pointer_reuse`, `mixed_finger_pen`, `cancel_stream`, `flag_canceled`, `axes_missing`, `long_stroke`, and `prediction_replaced`.
   - Verified in `F06ReplayFixturesAndBrushesTest` that real samples preserve exact historical ordering, `prediction_replaced` discards predicted samples upon commit, and `cancel_stream` / `flag_canceled` leave `0` phantom strokes.

2. **Android `MotionEvent` Adapter, Axis Capability Reporting & Immutable Batch Handoff (`F06.2`):**
   - Implemented `extractInkBatchFromMotionEvent`, `extractInkBatchFromAndroidEventData`, `DeviceAxisCapabilities`, and `LeasedInkInputBatch` in `modules/tn-platform/src/main/kotlin/inkspace/platform/InputTransport.kt`.
   - Extracts `historySize` historical coordinates (`getHistoricalX/Y/Pressure/Tilt/Orientation/EventTime`) before current pointer samples, maps `ACTION_HOVER_MOVE` / `ACTION_HOVER_EXIT`, detects `ACTION_CANCEL` and `FLAG_CANCELED`, and classifies passive capacitive pens (`TOOL_TYPE_FINGER`) honestly as touch without fabricating pressure/tilt readings.
   - Verified in `F06MotionEventAdapterTest` (7 test cases) that `InputBatch` never exposes mutable backing arrays after handoff and palm touch does not cancel an active stylus pointer.

3. **Versioned Brushes, Corner-Preserving Smoothing, `±8192 pt` Origin Rebasing & Bounded Resident Spooling (`F06.3`):**
   - Implemented `CornerPreservingSmoother` (`modules/tn-ink/src/main/kotlin/inkspace/ink/Smoothing.kt`) with turning-angle (`~60°`) sharp-corner preservation and `±8192 pt` local chunk origin rebasing.
   - Implemented `BrushPipeline` (`Brush.kt`) and `InkGeometryPort` (`Geometry.kt`) for all 5 brush kinds (`Ball`, `Fountain`, `Brush`, `Pencil`, `Highlighter`), supporting pressure/tilt modulation, end caps (`ROUND`, `FLAT`, `TAPERED`), line patterns (`SOLID`, `DASHED`, `DOTTED`), opacity, and single-mask highlighter rendering.
   - Implemented bounded resident draft window (`<= 8 MiB` default, configurable for testing) with provisional chunk spooling via `StrokeSpoolSink` (`InputSession.kt`). Verified that `long_stroke` (`>= 10x` resident window) spools chunks and commits as a single undoable `EditPlan`, while `DiskFull` aborts explicitly with `InputError.SpoolFailed` and discards staged chunks without silent truncation.

4. **Draft-to-Commit Runtime Integration, Vector Persistence & `W1` Latency Benchmarks (`F06.4` & `F06.5`):**
   - Wired `InkEngine` and `InkGeometryPort` into `Runtime` (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/Runtime.kt`), connecting live draft strokes, hover overlays, stylus-only finger navigation, temporary eraser button (`BUTTON_STYLUS_SECONDARY`), and single-step `EditPlan` commit + Undo/Redo + SQLite persistence.
   - Verified in `F06InkRuntimeIntegrationAndLatencyTest` that draft and committed strokes render pixel-identically on real samples (`0` mismatched pixels), committed strokes remain vector-editable at `4x` zoom after document reopen, `pen + save + scroll` performs `0` Main-thread I/O operations, and `W1` meets all latency budgets (`docs/evidence/F06/w1-ink-latency-raw.json`).

## 2. Verification Gates Executed

| Gate Command | Result |
|---|---|
| `./gradlew :tn-ink:test` | **PASS** (5/5 tests in `F06ReplayFixturesAndBrushesTest`) |
| `./gradlew :tn-platform:testDebugUnitTest` | **PASS** (7/7 tests in `F06MotionEventAdapterTest` + existing platform tests) |
| `./gradlew :tn-runtime:testDebugUnitTest` | **PASS** (3/3 tests in `F06InkRuntimeIntegrationAndLatencyTest` + existing F04 runtime tests) |
| `./gradlew apiDump && ./gradlew architectureCheck apiCheck requirementsCheck` | **PASS** |
| `./gradlew ktlintCheck detekt jvmTestAll androidUnitTestAll lint assembleDebug` | **PASS** |
