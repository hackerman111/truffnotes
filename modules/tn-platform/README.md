# `:tn-platform` — Android System Adapters & Generation Handles

- **Owner Phase:** F04
- **Dependencies:** `:tn-core` only
- **Responsibilities:**
  - `SessionHandle` / `FdResourceHandle`: generation-stamped closeable handles with stale rejection, idempotent double-close, and zero `Activity` retention.
  - `InputTransport`: pooled primitive sample batches (`<= 64 KiB`), finite/monotonic payload validation, freeze-on-handoff, and pool reuse only after cancellation/release.
  - `SurfaceHost`: `SurfaceEpoch` and `ResizeGeneration` tracking with acknowledged teardown (`SurfaceTeardownAck`) and lightweight `SavedSessionBundle` state serialization.
- **Verification:** `./gradlew :tn-platform:testDebugUnitTest`
