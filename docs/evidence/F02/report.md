# F02 Evidence Report: Shared Document Model & Atomic Transactions

```text
Phase / task / Requirement IDs: F02 (F02.1, F02.2, F02.3, F02.4, F02.5) / R07, R10, R15, R17, R28, R31
Revision or source fingerprint:
  truffnotes.md: 312c200fcac56e2f418bec61d4d8eee2ef12f8894d530352b94bb6e465a8566a
  goodnotes_red.md: 5eb666b3806238aaa8948346dd4a9a064bbc58a5ea6efcb18353d1dcd02191ad
  images.zip: 3abc92bef98d6414709d8f8432f6d348e08fbc511341465a9b1a8088fcaa0f6a
Changed paths:
  modules/tn-core/src/main/kotlin/inkspace/core/error/Errors.kt
  modules/tn-core/src/main/kotlin/inkspace/core/unit/Units.kt
  modules/tn-core/src/main/kotlin/inkspace/core/read/DocumentRead.kt
  modules/tn-core/src/main/kotlin/inkspace/core/model/DocumentModel.kt
  modules/tn-core/src/main/kotlin/inkspace/core/codec/CoreCodec.kt
  modules/tn-core/src/main/kotlin/inkspace/core/mutation/Mutations.kt
  modules/tn-core/src/test/kotlin/inkspace/core/F02TransactionTest.kt
  docs/contracts/core-v1.md
  docs/contracts/core-api-dump.txt
  docs/superpowers/plans/2026-11-05-truffnotes-android.md
  requirements.json
  docs/evidence/F02/cases.json
  docs/evidence/F02/w2-allocations-raw.json
  docs/evidence/F02/report.md
Public API/schema changes:
  Added AffineTransform2D, ObjectStyle, PageDescriptor, LayerDescriptor, SceneObject, ChunkedObjectDirectory, DocumentSnapshot, DocumentState, SnapshotLease, SnapshotPinTracker in inkspace.core.model
  Added TransformObject, UpdateObjectStyle, SetObjectLock, AddLayer, RemoveLayer, ReorderLayer, SetLayerLock, SetLayerStyle, AddPage, RemovePage, ReorderPage, ResizePage, applyPlan, replayWriteSet, History in inkspace.core.mutation
  Added WriteRecord, WriteSet, WriteSetCodec, CoreCodec.computePayloadDigest in inkspace.core.codec
  Added EditError.EntityNotResident and EditError.NoOpPlan in inkspace.core.error
  Updated docs/contracts/core-api-dump.txt via ./gradlew apiDump
Commands and exit codes:
  rtk proxy ./gradlew :tn-core:test :tn-test-support:test -> exit 0
  rtk proxy ./gradlew ktlintCheck detekt -> exit 0
  rtk proxy ./gradlew jvmTestAll --rerun-tasks -> exit 0
  rtk proxy ./gradlew architectureCheck apiCheck requirementsCheck -> exit 0
  rtk proxy ./gradlew androidUnitTestAll lint assembleDebug -> exit 0
Device / API / firmware / ABI / GPU / build profile:
  Host JVM: Linux x86_64, OpenJDK 27 (JVM_17 bytecode target), Gradle 9.1.0, Kotlin 2.3.20
Fixture IDs and hashes:
  fixtures/workloads/manifest.json (seed 20261105, W2 100,000 objects profile)
  fixtures/documents/v1-sample-plan.json
Tests: passed / failed / skipped (with reasons):
  F01ContractTest (:tn-core:test): 5 passed, 0 failed, 0 skipped
  F02TransactionTest (:tn-core:test): 10 passed (atomic_plan, inverse_roundtrip, revision_precondition, locked_entities, group_cycles, missing_vs_nonresident, chunked_directory_w2_single_edit_allocations, changeset_bounds_and_gesture_grouping, property_10000_transaction_sequences_undo_redo [55,054 transactions across 10,000 seeded sequences], versioned_writeset_codec_and_replay_validation), 0 failed, 0 skipped
  TestSupportAndArchitectureTest (:tn-test-support:test): 4 passed, 0 failed, 0 skipped
Measurements: raw path, p50/p95/p99, sample count:
  W2 single-object edit incremental directory structure allocation (100,000 objects in DocumentState):
    raw path: docs/evidence/F02/w2-allocations-raw.json
    sample count: 100
    p50: 1904 bytes
    p95: 2160 bytes
    p99: 2256 bytes
    shared leaf chunks per single-object edit: 4095 / 4096 (99.975% structural sharing)
Remaining limitations / failing acceptance criteria:
  None for F02.
Status: verified
```

---

## 1. Summary of F02 Deliverables
- **F02.1 Atomic Validation & Preconditions:** `validatePlan` and `applyPlan` validate the entire mutation batch on a staging overlay before any state transition. Malformed batches, stale revisions (`StaleRevision`), version precondition mismatches (`PreconditionFailed`), object/layer locks (`LockedEntity`), group self/indirect cycles (`CycleDetected`), group depth > 32 (`LimitExceeded`), and `NotResident` vs `Missing` states (`EntityNotResident` vs `EntityNotFound`) are rejected atomically with zero partial mutation.
- **F02.2 Chunked Document Directory (`ChunkedObjectDirectory`):** Implemented a 64×64 two-level persistent radix tree (4,096 leaf chunks). Single-object edits on a 100,000-object W2 document clone only 1 leaf chunk out of 4,096 (`p50 = 1,904 B`, `p95 = 2,160 B`, `p99 = 2,256 B`), avoiding $O(N)$ collection copies.
- **F02.3 Exact `ChangeSet`, Inverse Deltas & Gesture Grouping:** `applyPlan` computes exact `oldBounds` and `newBounds` unions over net changed objects, rejects no-op plans (`EditError.NoOpPlan`) without advancing `Revision`, and `History` coalesces consecutive steps sharing a `GestureId` into a single logical undo/redo operation.
- **F02.4 Property Test (10,000 Seeded Sequences):** `property_10000_transaction_sequences_undo_redo` (seed `20261105`) executes 10,000 multi-step sequences (~55,000 transactions covering object creation, style, move/transform, deletion, grouping, layer CRUD/style, page CRUD/resize/reorder), verifying that full Undo restores the exact initial `DocumentSnapshot` fingerprint and full Redo restores the exact final `DocumentSnapshot` with valid scene ordering, links, and asset references.
- **F02.5 Versioned `WriteSet` Codec & Replay Validation:** `WriteSetCodec` encodes/decodes `WriteSet` and `WriteRecord` via canonical CBOR/JSON. Payload digests (`CoreCodec.computePayloadDigest`) use deterministic 64-bit FNV-1a over CBOR bytes independent of JVM object layout. `replayWriteSet` validates and applies through the single `validatePlan` → `applyPlan` reducer and rolls back if any record digest or version is tampered with. `SnapshotPinTracker` measures active `SnapshotLease` pins together with `History.pinnedBytes`.
