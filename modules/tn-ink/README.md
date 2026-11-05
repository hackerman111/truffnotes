# `:tn-ink` — Handwriting Ingest, Versioned Brushes, Corner-Preserving Smoothing & `GeometryPort`

## Responsibility
- Single authoritative owner of handwriting input ingest (`InkEngine.ingest(batch: InputBatch, ctx: InputContext)`), per-pointer active stroke sessions (`InputSession`), palm rejection, stylus-only policy, temporary eraser button handling, corner-preserving stroke smoothing (`Smoothing`), and canonical vector geometry (`InkGeometryPort : GeometryPort`) for all 5 brush kinds (`Ball`, `Fountain`, `Brush`, `Pencil`, `Highlighter`).
- Pure Kotlin/JVM module depending ONLY on `:tn-core`.

## Public API
- `InkEngine.ingest(batch: InputBatch, ctx: InputContext): Outcome<InkOutput, InputError>`
- `InkGeometryPort : GeometryPort`
- `BrushPipeline` & `CornerPreservingSmoother`
- `InMemoryStrokeSpoolSink : StrokeSpoolSink`

## State Ownership & Budgets
- Confined to the Engine actor thread when invoked from `:tn-runtime`.
- Bounded resident draft window: `<= 8 MiB` (`MemoryCategory.DRAFT_STROKE_WINDOW`). Older sample chunks of long strokes are spooled to `StrokeSpoolSink` provisional staging without creating intermediate document revisions; `UP` publishes all chunks in one atomic undoable `EditPlan`, while `CANCEL` / `FLAG_CANCELED` discards staged chunks (`0` phantom strokes).
- Strictly separates `RealSample` from `PredictedSample` (`0` predicted samples ever enter `EditPlan`, `DocumentSnapshot`, `Undo`, or SQLite).
