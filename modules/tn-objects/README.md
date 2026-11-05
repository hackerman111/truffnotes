# `:tn-objects` — Selection, Hit-Testing, Eraser, Transforms & Groups

## Responsibility
Single owner of:
- Two-stage spatial query (`DocumentRead.queryBounds`) followed by precise vector/polygon hit-testing (`HitTest.kt`).
- Rectangular and freehand lasso selection (`CONTAINS` vs `INTERSECTS`), `REPLACE` / `ADD` / `SUBTRACT` combinators, layer/kind filters, and edit-inside-group context (`Selection.kt`).
- Move, scale, rotate, flip, style updates (`color`, `strokeWidthPt`, `opacity`), z-order reordering, cross-layer transfer, and non-mutating live `PreviewTransformOverlay` (`Transform.kt`).
- Nested group creation/ungrouping (`depth <= 32`, cycle rejection) and single-application of group transforms without double-transforming child members (`Group.kt`).
- `Precision`, `Segment`, `Stroke`, and `Object` eraser modes with coordinate/pressure/tilt/time interpolation at segment clip boundaries (`Erase.kt`).
- `ObjectPlanner.plan(read: DocumentRead, action: ObjectAction, ctx: PlanContext): Outcome<EditPlan, FeatureError>` and `ClipboardBundle` copy/cut/paste/duplicate with stable ID/group/connector remapping (`ObjectPlanner.kt`).

## Module Dependencies
- Production: `:tn-core` ONLY (receives `GeometryPort` via `PlanContext`, zero direct dependency on `:tn-ink`).
- Test: `:tn-test-support`, JUnit 5.

## Budgets & Verification Commands
- Spatial query (`W2`, up to `1,000` candidates): `p95 <= 8.0 ms`, no full-document geometry scan.
- Transform inverse round-trip error: `<= 0.01 pt`.
- Max group nesting depth: `32`.
- Commands: `./gradlew :tn-objects:test`
