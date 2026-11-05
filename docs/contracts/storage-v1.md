# Local Storage Contract v1 (`:tn-store`)

## 1. Directory & Database Layout

Each document is isolated under its own directory rooted at `documents/<docId>/`:

```text
documents/<docId>/
  document.sqlite          # Authoritative SQLite database (BundledSQLiteDriver)
  document.sqlite-wal      # Write-Ahead Log
  document.sqlite-shm      # Shared-memory WAL index
  assets/<prefix2>/<hash>  # Content-addressed immutable binary assets (SHA-256)
  staging/<stagingId>.part # Temporary staging files before fsync + atomic move
  drafts/<gesture>_<idx>.chunk # Provisional in-progress stroke draft chunks
  versions/<versionId>.sqlite  # Consistent VACUUM INTO checkpoint snapshots
```

## 2. SQLite Configuration & Pragmas (`F03.2`)

Every connection opened by `DocumentStore` via `androidx.sqlite.driver.bundled.BundledSQLiteDriver` enforces:

- `PRAGMA journal_mode = WAL;`
- `PRAGMA synchronous = FULL;` (`2`)
- `PRAGMA foreign_keys = ON;` (`1`)
- Single writer lock (`ReentrantLock`) serializing all state-mutating operations (`Open`, `StageDraftChunk`, `Commit`, `Flush`, `Close`, `Backup`, `Version`, `Collect`).

### Schema Migration `0001_document.sql`

- `schema_migrations(version, applied_at_ms)`
- `document_meta(document_id, document_kind, durable_revision, updated_at_ms)`
- `pages(page_id, space_id, ordinal, width_pt, height_pt)`
- `layers(layer_id, page_id, ordinal, name, opacity, is_visible, is_locked)`
- `scene_objects(object_id, space_id, layer_id, min_x, min_y, max_x, max_y, version, is_locked, payload_digest, encoded_cbor)`
- `transaction_log(transaction_id, base_revision, new_revision, writeset_cbor, committed_at_ms)`
- `asset_refs(asset_id, revision, size_bytes, sha256_hex)`
- `checkpoint_versions(version_id, revision, label, is_pinned, backup_rel_path, created_at_ms)`
- `spilled_history(seq_id, gesture_id, label, forward_cbor, inverse_cbor, byte_size)`

## 3. Atomic Commit & Idempotency Protocol (`R28`, `R29`, `R31`)

1. **Idempotency Check:** Before applying a `StorageRequest.Commit`, `DocumentStore` checks `transaction_log` for `transactionId`. If already committed, it returns `StorageReply.Committed(DurableAck(..., wasDeduplicated = true))` without re-applying mutations.
2. **Stale Base Check:** If `expectedDurableBase != durableRevision`, fails with `StorageError.StaleDurableBase(expected, actual)`.
3. **Asset-Before-Reference Ordering (`F03.3`):**
   - Binary assets are written to `staging/<stagingId>.part`, flushed with `FileDescriptor.sync()` (`fsync`), hashed with SHA-256 (`AssetId("sha256:<hex>")`), and atomically moved (`StandardCopyOption.ATOMIC_MOVE`) to `assets/<prefix2>/<hash>`.
   - During `Commit`, every `AssetId` referenced in `writeSet` or `stagedAssets` is verified on disk (`verifyPublishedAsset`). Missing or corrupted assets abort the commit before touching SQLite with `StorageError.MissingAssetReference`.
4. **Core Reducer Replay (`F03.4`):**
   - `WriteSet` replay always delegates to `:tn-core`'s `replayWriteSet(state, writeSet, limits)` (`validatePlan` → `applyPlan`). `:tn-store` never implements a second mutation reducer.
5. **Single SQLite Transaction:**
   - `document_meta`, `pages`, `layers`, `scene_objects`, `asset_refs`, and `transaction_log` are updated inside a single `BEGIN IMMEDIATE TRANSACTION ... COMMIT`.
   - Provisional draft chunks (`StageDraftChunk`) are written to `drafts/` without advancing `durable_revision` or creating a revision record.

## 4. Resource Leases, Checksum Verification & Lease-Aware GC (`R32`, `F03.3`, `F03.4`)

- `ResourcePort.read(ResourceRequest)` checks the byte budget (`ResourceError.ResourceExhausted`), verifies file existence (`ResourceError.ResourceNotFound`), and verifies the SHA-256 checksum of the file bytes on disk. Any bitrot or truncation returns `ResourceError.CorruptedResource` and is never masked with a blank/empty image.
- `StorageRequest.Collect` scans `assets/` and deletes only orphan files that are:
  1. Not referenced in `asset_refs`,
  2. Not in `pinnedAssets`, and
  3. Have `activeLeaseCount(assetId) == 0`.

## 5. Online Backup & Checkpoint Versions (`F03.1`, `F03.4`)

- `StorageRequest.Backup` and `StorageRequest.Version` execute `VACUUM INTO '<target_path>'` on the live WAL connection, producing a self-contained, transactionally consistent SQLite database snapshot without blocking readers or requiring connection closure, and copy reachable `assets/`.

## 6. Platform `fsync` & Crash-Recovery Assumptions (`F03.5`)

- **Host JVM (Linux ext4/xfs) & Android (f2fs/ext4):**
  - `FileOutputStream.fd.sync()` maps to POSIX `fsync(2)` on the staged asset file descriptor before `ATOMIC_MOVE` into `assets/<prefix2>/<hash>`.
  - SQLite `PRAGMA journal_mode = WAL` + `PRAGMA synchronous = FULL` issues `fsync` on the `-wal` file upon every `COMMIT` and on the main database file during checkpoints.
  - If power loss or `SIGKILL` truncates an uncommitted tail frame at the end of `document.sqlite-wal`, SQLite WAL recovery validates frame checksums sequentially and recovers the exact committed prefix up to the last valid commit frame.
  - On physical Android target hardware (`minSdk = 29`), `connectedDebugAndroidTest` (`StoreAndroidInstrumentedTest`) verifies `BundledSQLiteDriver` JNI loading, WAL/FULL pragma enforcement, and F2FS/ext4 `fsync` durability.
