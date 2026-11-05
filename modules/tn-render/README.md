# `:tn-render` — Unified Renderer, Spatial Culling & Resource Caches

## Responsibility
Single owner of:
- `CanvasReplay`: unified replay of scene objects, draft strokes, and ephemeral overlays across `Surface`, `Bitmap` snapshots, and `PdfDocument` overlays.
- Spatial frustum culling (`DocRect` visibility culling for pages and objects).
- Scale-bucketed derived `Path` / `TextLayout` caches (`<= 32 MiB`) and raster `TileArtifact` caches (`<= 32 MiB`).
- Controlled graphics target budget (`<= 96 MiB`), max `2` pinned render snapshots per session, and acknowledged `SurfaceEpoch` teardown.

## Public API
- `Renderer.render(request: RenderRequest): Outcome<FrameStats, RenderError>` (confined to render executor)
- `Renderer.renderWithReply(request: RenderRequest): Outcome<RenderReply, RenderError>`
- `Renderer` implements `RenderPort` (`trySubmit(job: RenderJob): Outcome<RequestId, SubmitError>`)
- `GoldenImageComparator`: deterministic image comparator with antialiasing tolerance (`<= 1 px` edge, `<= 2` per 8-bit channel outside edge).

## Verification Commands
```sh
./gradlew :tn-render:testDebugUnitTest
./gradlew :tn-render:assembleDebugAndroidTest
```
