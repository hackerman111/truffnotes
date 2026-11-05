# `:tn-ui` — Compose Screens & Baseline Session Controller

- **Owner Phase:** F04 (baseline screens), F12/F15 (full editor UI)
- **Dependencies:** `:tn-core` and Jetpack Compose / AndroidX Lifecycle
- **Responsibilities:**
  - `BaselineSessionController`: non-blocking UI state coordinator over `AppGateway`, tracking `SaveStatus`, `ViewportState`, pending overflow input lock, error banner state, and `CallbackDurationTracker` (`p95 <= 1.0 ms`).
  - `BaselineEditorScreen`: Compose baseline UI supporting portrait/landscape, Edit/Read-Only, Focus mode, save progress/error indicators, and `SurfaceView` attachment.
- **Verification:** `./gradlew :tn-ui:testDebugUnitTest`
