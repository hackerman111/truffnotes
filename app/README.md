# `:app` — Android Composition Root

- **Owner Phase:** F04
- **Dependencies:** `:tn-core`, `:tn-runtime`, `:tn-platform`, `:tn-ui`
- **Responsibilities:**
  - `TruffnotesApplication` and `MainActivity`: wires `Runtime.start`, `PlatformPorts`, `InputTransport`, `SurfaceHost`, and `BaselineEditorScreen`.
  - Handles Activity rotation/resize (`SavedSessionBundle`), `onStop` background flush hints, and `onTrimMemory` pressure callbacks without leaking `Activity` or `Surface` instances.
- **Verification:** `./gradlew :app:testDebugUnitTest :app:assembleDebug`
