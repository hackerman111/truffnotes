# Phase F05 Evidence Report — Единый renderer, culling и ресурсные caches (`:tn-render`)

```text
Phase / task / Requirement IDs: F05 (F05.1..F05.4) / R05, R07, R21, R34
Revision or source fingerprint: git branch main (post-F04 commit 10af98a)
Changed paths: modules/tn-core, modules/tn-test-support, modules/tn-render, modules/tn-runtime/build.gradle.kts, settings.gradle.kts, docs/contracts/core-api-dump.txt, docs/evidence/F05/*, requirements.json
Public API/schema changes: Added RenderError in :tn-core (inkspace.core.error.Errors.kt); extended GeometryRequest, PathArtifact, TextLayout, RenderJob.PdfOverlay in inkspace.core.ports.Ports.kt; added RenderContracts.kt (TileArtifact, DraftStrokeSceneItem, EphemeralOverlayItem, Scene, RenderTargetKind, RenderRequest, FrameStats, RenderReply) in :tn-core; implemented :tn-render (Scene.kt, Cache.kt, Tiles.kt, Surface.kt, Overlay.kt, CanvasReplay.kt, Renderer.kt)
Commands and exit codes:
  ./gradlew apiDump -> 0
  ./gradlew ktlintCheck detekt -> 0
  ./gradlew jvmTestAll -> 0
  ./gradlew architectureCheck apiCheck requirementsCheck -> 0
  ./gradlew androidUnitTestAll lint assembleDebug :tn-render:assembleDebugAndroidTest -> 0
Device / API / firmware / ABI / GPU / build profile: Host JVM (Linux x86_64, OpenJDK 26 targeting JVM 17) + Android SDK 36 / minSdk 29 debug APK & AndroidTest APK (arm64-v8a, x86_64)
Fixture IDs and hashes: Deterministic synthetic scenes and workload benchmarks W1 (100 pages x 100 strokes x 64 samples = 10,000 strokes) and W2 (500 pages, 100,000 strokes x 64 samples = 6,400,000 samples) with seed 20261105
Tests: passed: 8 F05 test cases across 3 JVM/Android unit suites (F05SceneAndGoldenTest: 4, F05CullingCachesAndBudgetsTest: 3, F05WorkloadBenchmarksTest: 1) / failed: 0 / skipped: 1 on-device AndroidTest execution (RendererAndroidInstrumentedTest compiled via :tn-render:assembleDebugAndroidTest; no physical tablet or AVD attached)
Measurements: raw path: docs/evidence/F05/w1-w2-frame-stats-raw.json; W1 (40 frames, 290 visible / 9,710 culled): p50=0.8181 ms, p95=2.9695 ms, p99=3.7127 ms, panRebuilds=0; W2 (40 frames, 490 visible / 99,510 culled): p50=6.4151 ms, p95=7.5953 ms, p99=15.0849 ms, panRebuilds=0 (budget: p95 <= 16.67 ms); memory: derivedCacheBytes=449,280 B (cap 32 MiB), tileCacheBytes=0 B (cap 32 MiB), peakControlledGraphicsBytes=2,097,152 B (cap 96 MiB), destroyRecreateCycles=20
Remaining limitations / failing acceptance criteria: Physical Android tablet / AVD execution of :tn-render:connectedDebugAndroidTest pending device attachment; host JVM & Android unit/lint/assemble gates PASS.
Status: verified (host & APK build gates); device instrumentation pending hardware attachment
```

## 1. Summary of Deliverables

1. **Unified Scene Graph, Camera-Relative Projection & Golden Comparator (`F05.1`):**
   - Implemented `ScenePreparer`, `CameraRelativeProjector`, and logarithmic `ScaleBucket` quantization (`~6.25%` step) in `modules/tn-render/src/main/kotlin/inkspace/render/Scene.kt`.
   - Implemented `CanvasReplay`, `SoftwareArgbSurface`, and `GoldenImageComparator` (`<= 1 px` edge antialiasing tolerance, `<= 2` per 8-bit channel outside edges) in `modules/tn-render/src/main/kotlin/inkspace/render/CanvasReplay.kt`.
   - Verified camera transforms, page clipping, z-order, hidden layers, layer opacity, single-mask highlighter compositing (no self-darkening on self-intersections), `Double -> Float` precision at `1e7 pt` world coordinates, and identical pixel rendering across `draft -> committed` transitions and shared `Draw` / `Snapshot` / `PdfOverlay` replay targets in `F05SceneAndGoldenTest`.

2. **Spatial Frustum Culling, Scale-Bucket Derived Cache & Dirty `ChangeSet` Invalidation (`F05.2`):**
   - Implemented spatial frustum culling via `DocumentSnapshot.queryBounds(visibleRect)` in `ScenePreparer.prepare(...)` so out-of-frustum objects are neither sorted nor rasterized.
   - Implemented `DerivedArtifactCache` (`modules/tn-render/src/main/kotlin/inkspace/render/Cache.kt`) keyed by `GeometryCacheKey(objectId, contentVersion, payloadDigest, brushId, smoothingVersion, scaleBucket)` and `TextLayoutCacheKey(objectId, contentVersion, payloadDigest, maxWidthBucket)`, storing packed primitive `FloatArray` relative offsets (`relXs`, `relYs`) for zero per-sample `Point` allocations on cache hits.
   - Verified `same_scene_pan` (`0` geometry rebuilds on pan) and selective `ChangeSet` dirty invalidation in `F05CullingCachesAndBudgetsTest`.

3. **Resource Budgets, LRU Eviction, Snapshot Pinning & SurfaceEpoch Lifecycle (`F05.3`):**
   - Implemented `RasterTileCache` (`32 MiB` default cap, LRU eviction) in `modules/tn-render/src/main/kotlin/inkspace/render/Tiles.kt` and `RenderSurfaceManager` (`96 MiB` controlled graphics cap, max `2` pinned snapshots per session, acknowledged `SurfaceEpoch` release, and explicit separation of `controlledGraphicsBytes` from `sdkReportedGraphicsBytes`) in `modules/tn-render/src/main/kotlin/inkspace/render/Surface.kt`.
   - Implemented `Renderer` (`modules/tn-render/src/main/kotlin/inkspace/render/Renderer.kt`) with thread-confined `renderExecutor` for screen `Draw` (`RenderError.WrongThread` on violation) and isolated `exportExecutor` + separate `CanvasReplay` instance for heavy `Snapshot` / `PdfOverlay` jobs.

4. **Synthetic `W1` & `W2` Workload Benchmarks & Android Instrumentation (`F05.4`):**
   - Implemented `F05WorkloadBenchmarksTest` (`W1`: `100` pages × `100` strokes × `64` samples = `10,000` strokes; `W2`: `500` pages, `100,000` strokes × `64` samples = `6,400,000` samples, `<= 1,000` visible objects in viewport; plus `20` surface `destroy/recreate` cycles) and generated `docs/evidence/F05/w1-w2-frame-stats-raw.json`.
   - Implemented `RendererAndroidInstrumentedTest` in `modules/tn-render/src/androidTest/kotlin/inkspace/render/RendererAndroidInstrumentedTest.kt`.

## 2. Verification Gates Executed

| Gate Command | Result |
|---|---|
| `./gradlew :tn-render:testDebugUnitTest` | **PASS** (8/8 tests: `F05SceneAndGoldenTest`, `F05CullingCachesAndBudgetsTest`, `F05WorkloadBenchmarksTest`) |
| `./gradlew :tn-render:assembleDebugAndroidTest` | **PASS** (`RendererAndroidInstrumentedTest` compiled into AndroidTest APK) |
| `./gradlew apiDump && ./gradlew architectureCheck apiCheck requirementsCheck` | **PASS** |
| `./gradlew ktlintCheck detekt jvmTestAll androidUnitTestAll lint assembleDebug` | **PASS** |
