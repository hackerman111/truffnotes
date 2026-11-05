# Phase F07 Evidence Report — Выделение, transforms, группы и ластик (`:tn-objects`, `:tn-core`, `:tn-runtime`)

```text
Phase / task / Requirement IDs: F07 (F07.1..F07.5) / R08, R09, R10, acceptance22, acceptance23, acceptance24, acceptance25 (contributes to R31)
Revision or source fingerprint: git branch main (post-F06 commit b1825ae)
Changed paths: modules/tn-core, modules/tn-objects, modules/tn-runtime, settings.gradle.kts, docs/contracts/core-api-dump.txt, docs/evidence/F07/*, requirements.json
Public API/schema changes: Added FeatureError in :tn-core (inkspace.core.error.Errors.kt); extended AffineTransform2D and LeafChunk in :tn-core (DocumentModel.kt); updated MoveObject inverse in Mutations.kt; added ObjectContracts.kt (SelectionMode, SelectionRule, SelectionCombinator, ObjectKindFilter, SelectionFilter, SelectionQuery, SelectionSet, FlipAxis, ZOrderCommand, StylePatch, EraserMode, EraserShape, EraserLayerPolicy, EraserSpec, TransferId, ClipboardObjectEntry, ClipboardBundle, PreviewTransformOverlay, PlanContext, ObjectAction) in :tn-core; implemented :tn-objects (HitTest.kt, Selection.kt, Group.kt, Transform.kt, Erase.kt, ObjectPlanner.kt); wired selectObjects, submitObjectAction, and copySelectionToClipboard into :tn-runtime (Runtime.kt)
Commands and exit codes:
  ./gradlew apiDump -> 0
  ./gradlew ktlintCheck detekt -> 0
  ./gradlew jvmTestAll -> 0
  ./gradlew architectureCheck apiCheck requirementsCheck -> 0
  ./gradlew androidUnitTestAll lint assembleDebug -> 0
Device / API / firmware / ABI / GPU / build profile: Host JVM (Linux x86_64, OpenJDK 26 targeting JVM 17) + Android SDK 36 / minSdk 29 debug APK (arm64-v8a, x86_64)
Fixture IDs and hashes: Deterministic generated workloads (10,000 generated Undo/Redo edits + W2 100,000-stroke spatial query benchmark) with seed 20261105
Tests: passed: 11 F07 test cases across 3 JVM/Android unit suites (F07ObjectsContractAndEraserTest: 8, F07PropertyAndSpatialBenchmarkTest: 2, F07RuntimeObjectsIntegrationTest: 1) / failed: 0 / skipped: 0
Measurements: raw path: docs/evidence/F07/w2-spatial-query-raw.json; W2 (500 pages, 100,000 strokes x 64 samples, 1,000 candidates in query region, 30 measured runs): w2_freehand_lasso_1000_candidates_ms p50=4.7849 ms, p95=5.4094 ms (budget <= 8.0 ms); w2_segment_eraser_1000_candidates_ms p50=4.8621 ms, p95=5.3168 ms (budget <= 8.0 ms); generated_undo_redo_edits_verified=10000 (0 drift or invariant violations)
Remaining limitations / failing acceptance criteria: Physical Android tablet gesture latency validation pending hardware attachment; all host JVM & Android unit/lint/assemble gates PASS.
Status: verified
```

## 1. Summary of Deliverables

1. **Contract Test Suite & Core Object Contracts (`F07.1`):**
   - Added `FeatureError` (`EmptySelection`, `EntityNotFound`, `TargetLockedOrHidden`, `TargetNotResident`, `GroupDepthExceeded`, `GroupCycleDetected`, `InvalidParameters`, `EditRejected`) and `ObjectContracts.kt` in `:tn-core`.
   - Registered pure Kotlin/JVM module `:tn-objects` depending strictly on `:tn-core` (verified by `./gradlew architectureCheck`), with `GeometryPort` (`InkGeometryPort`) injected by `:tn-runtime`.
   - Verified all 8 contract tests in `F07ObjectsContractAndEraserTest`: `lasso_contains_intersects`, `add_subtract_selection`, `hidden_locked`, `transform_inverse`, `nested_group_once`, `z_order`, `segment_interpolation`, and `clipboard_copy_cut_paste_remap` (including mixed selection of handwriting strokes and `Textbox` objects without merging them).

2. **Two-Stage Spatial Index & Precise Geometric Selection (`F07.2`):**
   - Implemented `HitTest` (`modules/tn-objects/src/main/kotlin/inkspace/objects/HitTest.kt`) and `SelectionEngine` (`Selection.kt`) using `DocumentRead.queryBounds` candidate culling followed by exact vector/polygon hit-testing (`CONTAINS` vs. `INTERSECTS`) for freehand lasso and rectangle selection, with `REPLACE` / `ADD` / `SUBTRACT` combinators, layer/kind filters, and strict exclusion of hidden, locked, or non-resident (`Evicted`) objects.

3. **Transforms, Z-Order, Layer Moves & Nested Groups (`F07.3`):**
   - Implemented `GroupHierarchy` (`Group.kt`) and `TransformPlanner` (`Transform.kt`) supporting `Move`, `Scale`, `Rotate`, `Flip`, `ApplyStyle`, `Delete`, `Duplicate`, `ChangeZOrder` (`BRING_TO_FRONT`, `SEND_TO_BACK`, `BRING_FORWARD`, `SEND_BACKWARD`), `MoveToLayer`, `Group`, `Ungroup`, and non-mutating live preview (`buildPreview`).
   - Enforces maximum group nesting depth `<= 32`, cycle detection, `editInsideGroup` (`enteredGroupId`), and deduplicated leaf expansion so moving or transforming a nested group applies the transform to each leaf member exactly once (`nested_group_once`).

4. **Precision / Segment / Stroke / Object Eraser (`F07.4`):**
   - Implemented `ErasePlanner` (`Erase.kt`) supporting `PRECISION`, `SEGMENT`, `STROKE`, and `OBJECT` modes with `CIRCLE` / `SQUARE` tips (`0.1..500.0 pt`), `ACTIVE_LAYER_ONLY` / `VISIBLE_UNLOCKED_LAYERS`, and kind filtering prior to geometric clipping.
   - `SEGMENT` and `PRECISION` modes use bisection boundary clipping and linear interpolation of `relX`, `relY`, `pressure`, `tilt`, and `timeDeltaMs`, replacing split strokes with editable vector `StrokePayload` sub-strokes while never erasing `Image`, `Text`, or `Shape` objects unless `OBJECT` mode is explicitly selected.

5. **Clipboard Remapping, `10,000`-Edit Undo/Redo Property Test & `W2` Spatial Benchmark (`F07.5`):**
   - Implemented `ObjectPlanner.copyToClipboard`, `cutToClipboard`, `Paste`, and `Duplicate` (`ObjectPlanner.kt`) with deterministic remapping of stable `ObjectId`s, nested `GroupPayload.members`, and `ConnectorPayload` (`sourceId`/`targetId`), plus `TransferId` contract for cross-document transfer.
   - Verified in `F07PropertyAndSpatialBenchmarkTest` that `10,000` generated object/group/eraser edits round-trip through Undo and Redo with `0` drift, and that `W2` (`1,000` candidates in `100,000` strokes) achieves `p95 = 5.4094 ms` (freehand lasso) and `p95 = 5.3168 ms` (segment eraser), both well within the `<= 8.0 ms` budget (`docs/evidence/F07/w2-spatial-query-raw.json`).
   - Verified end-to-end runtime integration (`selectObjects`, `submitObjectAction`, `copySelectionToClipboard`, SQLite persistence, and `4x` zoom vector rendering) in `F07RuntimeObjectsIntegrationTest`.

## 2. Verification Gates Executed

| Gate Command | Result |
|---|---|
| `./gradlew :tn-objects:test` | **PASS** (10/10 tests in `F07ObjectsContractAndEraserTest` + `F07PropertyAndSpatialBenchmarkTest`) |
| `./gradlew :tn-runtime:testDebugUnitTest` | **PASS** (`F07RuntimeObjectsIntegrationTest` + all F04/F06 runtime tests) |
| `./gradlew apiDump && ./gradlew architectureCheck apiCheck requirementsCheck` | **PASS** |
| `./gradlew ktlintCheck detekt jvmTestAll androidUnitTestAll lint assembleDebug` | **PASS** |
