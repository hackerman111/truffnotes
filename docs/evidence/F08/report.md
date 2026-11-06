# Phase F08 Evidence Report — Геометрические фигуры и автомат жестов (`:tn-core`, `:tn-objects`, `:tn-ink`, `:tn-render`, `:tn-runtime`)

```text
Phase / task / Requirement IDs: F08 (F08.1..F08.5) / R11, R12, R13, acceptance26, acceptance27, acceptance28, acceptance29, acceptance30 (contributes to R31)
Revision or source fingerprint: git branch main (post-F07 commit 0ce8c21)
Changed paths: modules/tn-core, modules/tn-objects, modules/tn-ink, modules/tn-render, modules/tn-runtime, docs/contracts/core-api-dump.txt, docs/evidence/F08/*, requirements.json
Public API/schema changes:
  - :tn-core/payload: Extended ShapeType (12 variants: Line, Arrow, Rectangle, Square, RoundedRectangle, Circle, Ellipse, Triangle, Polygon, Polyline, Curve, Arc); ShapePayload (fillColorArgb, cornerRadiusPt, endArrowhead, vertices, controlPoints); ConnectorAnchor (AnchorSide: TOP, BOTTOM, LEFT, RIGHT, AUTO_NEAREST, CUSTOM_OFFSET); DanglingEndpointPolicy (DELETE_CONNECTOR, DETACH_TO_POINT); ConnectorPayload (sourceAnchor, targetAnchor, resolvedStartPoint, resolvedEndPoint, routingPoints); ArrowheadStyle (NONE, OPEN_ARROW, FILLED_TRIANGLE, STEALTH, DIAMOND, CIRCLE).
  - :tn-core/objects: Added StrokeData; RecognitionConfig; ShapeCandidate; GridKind (BLANK, RULED, SQUARED, DOTTED, MILLIMETER, ISOMETRIC, CUSTOM); GridSpec; RulerUnit (CENTIMETERS, INCHES, MILLIMETERS, POINTS); RulerEdge (TOP_EDGE, BOTTOM_EDGE); RulerOverlayState; ProtractorOverlayState; GuideOrientation; GuideSpec; AlignmentGuideKind; AlignmentGuideLine; SnapConfig; SnappedPointResult; ObjectAlignmentResult; ObjectAction.ShapeAction (CreateShape, UpdateShapeVertices, UpdateShapeAppearance, CreateConnector, UpdateConnectorEndpoints, QuickDiagramCreateAndConnect); updated PlanContext with snapConfig and recognitionConfig.
  - :tn-core/ink: Added ActiveToolKind; StylusButtonAction; GesturePriorityLevel (SYSTEM_INTERCEPT, ACTIVE_OPERATION, EXPLICIT_TOOL, FINGER_NAVIGATION, STYLUS_GEOMETRIC_GESTURE, NORMAL_INK); GestureSettings (with resetToDefaults()); MultiTapGestureEvent; GestureProposal (ShapeHold, CircleToLasso, ScribbleErase, MultiFingerUndoRedo, SystemIntercept); GestureDecision; updated InkOutput and InputContext with gestureDecision and holdDurationMs.
  - :tn-core/render: Added EphemeralOverlayItem (RulerOverlay, ProtractorOverlay, AlignmentGuidesOverlay).
  - :tn-objects: Implemented ShapeRecognizer, ShapePlanner, SnapEngine; updated TransformPlanner with updateConnectedConnectors; updated ObjectPlanner for ShapeAction and DanglingEndpointPolicy.
  - :tn-ink: Implemented InkToolArbiter and GestureArbiter (6-level hierarchy, scribble-to-erase, circle-to-lasso, hold-to-shape, multi-tap undo/redo); instrumented ActivePointerStrokeState and InkEngine for hold duration tracking and gesture decision output.
  - :tn-render: Added rendering for RulerOverlay, ProtractorOverlay, and AlignmentGuidesOverlay in Overlay.kt using CameraTransform.
  - :tn-runtime: Integrated gesture arbitration, shape creation, scribble deletion, lasso query, ruler/protractor/grid snapping, and multi-tap undo/redo into Runtime actor.
Commands and exit codes:
  ./gradlew apiDump -> 0
  ./gradlew ktlintCheck detekt -> 0
  ./gradlew jvmTestAll -> 0
  ./gradlew architectureCheck apiCheck requirementsCheck -> 0
  ./gradlew androidUnitTestAll lint assembleDebug -> 0
Device / API / firmware / ABI / GPU / build profile: Host JVM (Linux x86_64, OpenJDK 26 targeting JVM 17) + Android SDK 36 / minSdk 29 debug APK (arm64-v8a, x86_64)
Fixture IDs and hashes: Frozen corpus fixtures in fixtures/input/f08-gesture-corpus.json and docs/evidence/F08/gesture-corpus-raw.json (seed 20261105)
Tests: passed: 38 F08 test cases across 4 test suites (F08ShapeAndConnectorTest: 13, F08RulerProtractorAndSnapTest: 7, F08GestureArbiterAndCorpusTest: 11, F08RuntimeShapesAndGesturesIntegrationTest: 7) / failed: 0 / skipped: 0
Measurements: raw path: docs/evidence/F08/gesture-corpus-raw.json; True Positive Rate = 97.54% (634/650 recognized, budget >= 95%); False Positive Rate = 0.0% (2200/2200 negative samples preserved, budget <= 0.5%); Ambiguous handwriting preservation = 100.0%.
Remaining limitations / failing acceptance criteria: Physical Android stylus hardware tap latency measurement pending device harness; all host JVM & Android unit/lint/assemble gates PASS.
Status: verified
```

## 1. Summary of Deliverables

1. **Contracts and Pure Data Models (`F08.1`):**
   - Extended `Payloads.kt` with all 12 `ShapeType` variants, `ShapePayload`, `AnchorSide`, `ConnectorAnchor`, `DanglingEndpointPolicy`, `ConnectorPayload`, and `ArrowheadStyle`.
   - Added `StrokeData`, `RecognitionConfig`, `ShapeCandidate`, 7 `GridKind`s, `GridSpec`, `RulerUnit`, `RulerEdge`, `RulerOverlayState`, `ProtractorOverlayState`, `AlignmentGuideLine`, and `SnapConfig` in `ObjectContracts.kt`.
   - Added `ActiveToolKind`, `GesturePriorityLevel`, `GestureSettings`, `MultiTapGestureEvent`, `GestureProposal`, and `GestureDecision` in `InkContracts.kt`.
   - Verified binary contract compatibility via `./gradlew apiDump` and `./gradlew apiCheck`.

2. **Shape Recognition and Connectors (`F08.2`):**
   - Implemented `ShapeRecognizer` in `modules/tn-objects/src/main/kotlin/inkspace/objects/Shape.kt`: recognizes all 12 geometric shapes (`Line`, `Arrow`, `Rectangle`, `Square`, `RoundedRectangle`, `Circle`, `Ellipse`, `Triangle`, `Polygon`, `Polyline`, `Curve`, `Arc`) with chord ratio, corner detection, isoperimetric quotient, arrow barb detection, circular arc curvature consistency, and strict reversal rejection for organic shading/hatching.
   - Evaluated against a frozen corpus of 2,200 negative samples (cursive loops, math symbols, freehand shading zig-zags, small dots): achieved **100% preservation** (0.0% false positive rate) and **97.54% true positive rate** on positive shapes (`docs/evidence/F08/gesture-corpus-raw.json`).
   - Implemented `ShapePlanner` for shape edits, vertex modification, appearance changes, and dynamic connector tracking (`updateConnectedConnectors`) following moved, scaled, and rotated objects.
   - Implemented `DanglingEndpointPolicy` (`DELETE_CONNECTOR` vs `DETACH_TO_POINT`) and atomic `QuickDiagramCreateAndConnect` transactions with single-step undo.

3. **Snapping Engine and Canvas Overlays (`F08.3`):**
   - Implemented `SnapEngine` in `modules/tn-objects/src/main/kotlin/inkspace/objects/Snap.kt`: ruler 2-edge snap (`TOP_EDGE` / `BOTTOM_EDGE`), cm / inch unit conversions, protractor arc/ray snap, 7 grid kinds (`BLANK`, `RULED`, `SQUARED`, `DOTTED`, `MILLIMETER`, `ISOMETRIC`, `CUSTOM`), 15° step angle snapping, and object alignment guides (dashed for centers, solid for edges).
   - Rendered overlays in `Overlay.kt` (`:tn-render`) with shared coordinates via `CameraTransform`.

4. **Unified 6-Level Gesture Arbiter (`F08.4`):**
   - Implemented `GestureArbiter` in `modules/tn-ink/src/main/kotlin/inkspace/ink/Gesture.kt` enforcing the strict precedence:
     1. `SYSTEM_INTERCEPT`
     2. `ACTIVE_OPERATION`
     3. `EXPLICIT_TOOL`
     4. `FINGER_NAVIGATION`
     5. `STYLUS_GEOMETRIC_GESTURE` (Hold-to-straighten, hold-to-shape, circle-to-lasso, scribble-to-erase)
     6. `NORMAL_INK`
   - Scribble-to-erase triggers only on Pen brushes (`BALL`, `FOUNTAIN`, `BRUSH`) and ignores `HIGHLIGHTER` or `PENCIL`.
   - Circle-to-lasso (`circle-lift-rehold`) detects perimeter re-hold and triggers selection without shape conversion.
   - 2-finger double-tap triggers Undo, 3-finger double-tap triggers Redo; single-finger double-tap is preserved for zoom.

5. **Runtime Integration and Persistence (`F08.5`):**
   - Integrated gesture arbitration, shape creation, scribble stroke deletion, lasso selection, snap configuration, and multi-tap undo/redo into `Runtime.kt` (`:tn-runtime`).
   - Verified `acceptance26`..`acceptance30` with SQLite persistence across reload and single-step undo/redo in `F08RuntimeShapesAndGesturesIntegrationTest`.

## 2. Verification Gates Executed

| Gate Command | Result |
|---|---|
| `./gradlew :tn-objects:test` | **PASS** (24/24 tests in `F08ShapeAndConnectorTest` + `F08RulerProtractorAndSnapTest`) |
| `./gradlew :tn-ink:test` | **PASS** (16/16 tests in `F08GestureArbiterAndCorpusTest` + `F06ReplayFixturesAndBrushesTest`) |
| `./gradlew :tn-runtime:testDebugUnitTest` | **PASS** (`F08RuntimeShapesAndGesturesIntegrationTest` + all previous runtime tests) |
| `./gradlew apiDump && ./gradlew architectureCheck apiCheck requirementsCheck` | **PASS** |
| `./gradlew ktlintCheck detekt jvmTestAll androidUnitTestAll lint assembleDebug` | **PASS** |
