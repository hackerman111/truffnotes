# Phase F03 Evidence Report — Надёжное локальное хранение и восстановление (`:tn-store`)

- **Date:** 2026-11-05
- **Branch:** `feat/f03-local-storage`
- **Requirements Covered:** `R28`, `R29`, `R31`, `R32`
- **Owned Paths:** `modules/tn-store`, `fixtures/corrupt/store/truncated-wal.bin`, `docs/contracts/storage-v1.md`

## 1. Summary of Deliverables

1. **Storage & Resource Ports (`F03.1`, `F03.2`):**
   - Extended `StorageRequest` (`Open`, `Read`, `StageDraftChunk`, `Commit`, `Flush`, `Close`, `Backup`, `Version`, `Collect`), `StorageReply`, `DurableAck`, `StagedAssetRef`, and `ResourceLease` release callbacks in `modules/tn-core/src/main/kotlin/inkspace/core/ports/Ports.kt`.
   - Implemented `DocumentStore` in `modules/tn-store/src/main/kotlin/inkspace/store/DocumentStore.kt` backed by `androidx.sqlite.driver.bundled.BundledSQLiteDriver` (`2.7.1`) with single-writer serialization (`ReentrantLock`) and bounded admission queue (`maxQueueDepth = 64`).
   - Enforced `PRAGMA journal_mode = WAL`, `PRAGMA synchronous = FULL` (`2`), and `PRAGMA foreign_keys = ON` (`1`) on every connection in `SchemaAndMigrate.kt` with SQL migration `0001_document.sql`.
   - Provisional draft chunks (`StageDraftChunk`) are persisted and `fsync`-ed to `drafts/` without advancing `durableRevision`.
   - `Commit` checks `transaction_log` by `TransactionId` for idempotency (`wasDeduplicated = true` on retry), verifies `expectedDurableBase`, validates all referenced/staged assets before opening the SQLite transaction, replays `WriteSet` through `:tn-core`'s `replayWriteSet` (`validatePlan` → `applyPlan`), and atomically updates `document_meta`, `pages`, `layers`, `scene_objects`, `asset_refs`, and `transaction_log`.

2. **Immutable Content-Addressed Assets & Lease-Aware GC (`F03.3`):**
   - Implemented `AssetsAndGc.kt`: stages binary assets in `staging/<stagingId>.part`, invokes `FileDescriptor.sync()` (`fsync`), computes SHA-256 (`AssetId("sha256:<hex>")`), and atomically moves to `assets/<prefix2>/<hash>`.
   - `ResourcePort.read(ResourceRequest)` verifies byte budget (`ResourceError.ResourceExhausted`), file existence (`ResourceError.ResourceNotFound`), and full SHA-256 integrity (`ResourceError.CorruptedResource`) so corrupted assets are never masked with an empty image.
   - `StorageRequest.Collect` removes orphan assets only when unreferenced in `asset_refs`, not in `pinnedAssets`, and `activeLeaseCount(assetId) == 0`.

3. **Lazy Chunk Reads, History Spill, Online Backup & Checkpoint Versions (`F03.4`):**
   - `StorageRequest.Read` loads requested `objectIds` or `spaceId` objects from `scene_objects` with `byteBudget` enforcement and returns a `ResourceLease`.
   - `spillHistoryEntry` / `loadSpilledHistory` persist evicted undo/redo mutations to `spilled_history`.
   - `StorageRequest.Backup` and `StorageRequest.Version` execute `VACUUM INTO` on the active WAL connection and copy reachable `assets/`.

4. **Child-Process Fault-Injection Harness (`F03.5`):**
   - `FaultStoreChildMain.kt` runs `1,000` seeded crash schedules (`seed = 20261105`) in a separate child JVM process across 6 fault scenarios (`DISK_FULL_ON_ASSET_WRITE`, `CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT`, `DISK_FULL_ON_DB_COMMIT`, `CRASH_DURING_DB_TRANSACTION`, `CRASH_AFTER_DB_COMMIT_BEFORE_ACK`, and `TRUNCATED_WAL_TAIL`), reopens `DocumentStore`, and verifies `durableRevision`, `contentFingerprint()`, and `PRAGMA integrity_check`.
   - Raw results saved in `docs/evidence/F03/fault-schedules-raw.json`:
     - `runs = 1000`, `totalCommittedTransactions = 2675`, `durablePrefixLossCount = 0`, `sqliteIntegrityFailures = 0`, `elapsedMs = 2077`.

## 2. Verification Gates Executed

| Gate Command | Result |
|---|---|
| `rtk proxy ./gradlew :tn-store:testDebugUnitTest` | **PASS** (9/9 tests: 8 contract tests + 1,000-schedule child-process fault runner) |
| `rtk proxy ./gradlew faultStore -Pseed=20261105 -Pruns=1000` | **PASS** (0 durable loss, 0 corruption, bounded heap) |
| `rtk proxy ./gradlew :tn-store:assembleDebugAndroidTest` | **PASS** (`StoreAndroidInstrumentedTest` compiled into APK with `libsqliteJni.so`) |
| `rtk proxy ./gradlew apiDump && rtk proxy ./gradlew architectureCheck apiCheck requirementsCheck` | **PASS** |
| `rtk proxy ./gradlew ktlintCheck detekt jvmTestAll androidUnitTestAll lint assembleDebug` | **PASS** |

> **Hardware / Device Gate Note (`:tn-store:connectedDebugAndroidTest`):** No physical Android tablet or AVD emulator is attached to this host (`adb devices` / `emulator -list-avds` empty). Per `docs/plan/ai-execution.md`, the instrumented test APK (`:tn-store:assembleDebugAndroidTest`) is compiled and verified, while on-device execution of `:tn-store:connectedDebugAndroidTest` remains pending physical device/AVD attachment without weakening the check.
