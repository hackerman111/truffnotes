# Phase F04 Evidence Report — Runtime, Android lifecycle и очереди (`:tn-runtime`, `:tn-platform`, `:tn-ui`, `:app`)

```text
Phase / task / Requirement IDs: F04 (F04.1..F04.5) / R01, R05, R29, R36, acceptance38, acceptance39, acceptance40, acceptance43, acceptance44
Revision or source fingerprint: git branch main (post-F03 commit 0ff135d)
Changed paths: modules/tn-core, modules/tn-test-support, modules/tn-platform, modules/tn-runtime, modules/tn-ui, app, docs/contracts/core-api-dump.txt, docs/evidence/F04/*, requirements.json
Public API/schema changes: Added SurfaceEpoch, ResizeGeneration, StartError, SubmitError.{ByteBudgetExceeded,StaleSession,StaleHandle,InvalidPayload,PendingPersistenceOverflow}, RuntimeContracts.kt (RuntimeConfig, PlatformPorts, AppCommand, AppEvent, EventBatch, AppGateway, ImmutableInputBatch, SaveStatus, ViewportState, MemoryCategory, MemoryPressureLevel) in :tn-core; defensive java.util.List.copyOf backing in DocumentSnapshot
Commands and exit codes:
  ./gradlew apiDump -> 0
  ./gradlew ktlintCheck detekt -> 0
  ./gradlew jvmTestAll -> 0
  ./gradlew architectureCheck apiCheck requirementsCheck -> 0
  ./gradlew androidUnitTestAll lint assembleDebug :tn-runtime:assembleDebugAndroidTest -> 0
Device / API / firmware / ABI / GPU / build profile: Host JVM (Linux x86_64, OpenJDK 26 targeting JVM 17, SQLite Bundled JVM 2.7.1) + Android SDK 36 / minSdk 29 debug APK & AndroidTest APK (arm64-v8a, x86_64)
Fixture IDs and hashes: Generated deterministic test documents and input batches (seed 20261105)
Tests: passed: 18 F04 test cases across 5 suites (F04TransportAndHandlesTest: 6, F04RuntimeQueuesAndMemoryTest: 8, F04LifecycleAndPersistenceTest: 1, CoreTransactionsTest: 1, F04BaselineUiControllerTest: 1, F04AppLifecycleAndCallbackProfileTest: 1) / failed: 0 / skipped: 1 on-device AndroidTest execution (RuntimeAndroidLifecycleInstrumentedTest compiled via :tn-runtime:assembleDebugAndroidTest; no physical tablet or AVD attached)
Measurements: raw path: docs/evidence/F04/callback-profile-raw.json, p50=0.005937 ms, p95=0.011874 ms, p99=0.033245 ms, max=0.104484 ms, sample count=550 (budget: p95 <= 1.0 ms)
Remaining limitations / failing acceptance criteria: Physical Android tablet / AVD execution of :tn-runtime:connectedDebugAndroidTest pending device attachment; host JVM & Android unit/lint/assemble gates PASS.
Status: verified (host & APK build gates); device instrumentation pending hardware attachment
```

## 1. Summary of Deliverables

1. **Executor Ownership, Bounded Queues & Quantum Scheduling (`F04.1`):**
   - Implemented `OwnerGuard` and `ConfinedSingleThreadExecutor` in `modules/tn-runtime/src/main/kotlin/inkspace/runtime/ExecutorsAndPool.kt` for dedicated `Engine`, `Render`, and `Store` single-thread executors.
   - Implemented `RuntimeQueues` in `modules/tn-runtime/src/main/kotlin/inkspace/runtime/RuntimeQueues.kt` with a `16 MiB` total queue byte cap and separate bounded queues for durable persistence (`256` envelopes / `8 MiB`), input batches (`4 MiB`, `64 KiB` max batch, lossless `MOVE` coalescing, `64` reserved terminal `UP`/`CANCEL` slots), latest-wins viewport/surface slots, and `mainEvents` (`256` envelopes / `2 MiB`, coalescing non-error events while keeping `errorEvents` in a separate `64`-slot ring until acknowledged).
   - Enforced quantum scheduling (`4` input batches -> `1` control/viewport -> `1` durable ack -> `1` worker completion) so continuous `MOVE` storms never starve `UP`/`CANCEL` or `Close`.

2. **MemoryGovernor, Snapshot Pinning & Bounded Worker Pool (`F04.2`):**
   - Implemented `MemoryGovernor` (`MemoryCategory` caps, `MemoryLease`, `RenderSnapshotPin` with max `2` pinned snapshots per session and `3` warm sessions) and `BoundedWorkerPool` (`1..4` threads, stamped generation cancellation) in `modules/tn-runtime/src/main/kotlin/inkspace/runtime/MemoryGovernor.kt`.

3. **Typed Input Transport, Handle Generations & Defensive Immutability (`F04.3`):**
   - Implemented `SessionHandleRegistry` and `FdResourceHandle` in `modules/tn-platform/src/main/kotlin/inkspace/platform/SessionHandle.kt`.
   - Implemented `InputTransport`, `PooledInputBuffer`, and `LeasedInputBatch` in `modules/tn-platform/src/main/kotlin/inkspace/platform/InputTransport.kt` using primitive arrays (`xs`, `ys`, `pressures`, `tilts`, `timestampsMs`) without per-sample `Point` allocations, invalidating sender tokens immediately upon handoff or cancel.
   - Enforced deep defensive immutability (`java.util.List.copyOf` and `SceneObject.frozen()`) in `DocumentSnapshot` (`modules/tn-core/src/main/kotlin/inkspace/core/model/DocumentModel.kt`).

4. **Save Watermark, SurfaceEpoch Teardown & 100-Cycle Rotation Durability (`F04.4`):**
   - Implemented `Runtime` (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/Runtime.kt`) and `SurfaceHost` / `SavedSessionBundle` (`modules/tn-platform/src/main/kotlin/inkspace/platform/SurfaceHost.kt`).
   - Continuous `durableRevision` watermark advances strictly in monotonic order; `SaveStatus` reports `SAVED`, `SAVING`, `UNSAVED_ERROR`, and `PENDING_OVERFLOW`.
   - Verified `100` consecutive portrait/landscape rotation, resize, surface destroy/recreate (`SurfaceTeardownAck`), background flush, and memory trim cycles followed by cold reopen in `F04LifecycleAndPersistenceTest`.

5. **Android Baseline Screens & Main Callback Profiling (`F04.5`):**
   - Implemented `BaselineSessionController` / `CallbackDurationTracker` and `BaselineEditorScreen` in `:tn-ui`, plus `TruffnotesAppContainer`, `TruffnotesApplication`, and `MainActivity` in `:app`.
   - Verified immediate input rejection under `PENDING_OVERFLOW` and profiled `550` main/UI callbacks in `docs/evidence/F04/callback-profile-raw.json` (`p95 = 0.011874 ms <= 1.0 ms`).

## 2. Verification Gates Executed

| Gate Command | Result |
|---|---|
| `./gradlew :tn-platform:testDebugUnitTest` | **PASS** (6/6 tests: `stale_handle`, `double_close`, `invalid_payload`, `mutate_after_handoff`, `pool_reuse_after_cancel`, `snapshot_alias`) |
| `./gradlew :tn-runtime:testDebugUnitTest` | **PASS** (10/10 tests: `F04RuntimeQueuesAndMemoryTest`, `F04LifecycleAndPersistenceTest`, `CoreTransactionsTest`) |
| `./gradlew :tn-ui:testDebugUnitTest` | **PASS** (1/1 test: `F04BaselineUiControllerTest`) |
| `./gradlew :app:testDebugUnitTest` | **PASS** (1/1 test: `F04AppLifecycleAndCallbackProfileTest`) |
| `./gradlew :tn-runtime:assembleDebugAndroidTest` | **PASS** (`RuntimeAndroidLifecycleInstrumentedTest` compiled into AndroidTest APK) |
| `./gradlew apiDump && ./gradlew architectureCheck apiCheck requirementsCheck` | **PASS** |
| `./gradlew ktlintCheck detekt jvmTestAll androidUnitTestAll lint assembleDebug` | **PASS** |
