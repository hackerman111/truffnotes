# F01 Evidence Report: Gradle Modules & Core Contract Freeze

```text
Phase / task / Requirement IDs: F01 (F01.1, F01.2, F01.3, F01.4) / R01, R28, R31, R34
Revision or source fingerprint:
  truffnotes.md: 312c200fcac56e2f418bec61d4d8eee2ef12f8894d530352b94bb6e465a8566a
  goodnotes_red.md: 5eb666b3806238aaa8948346dd4a9a064bbc58a5ea6efcb18353d1dcd02191ad
  images.zip: 3abc92bef98d6414709d8f8432f6d348e08fbc511341465a9b1a8088fcaa0f6a
Changed paths:
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  gradlew, gradlew.bat, gradle/wrapper/
  modules/tn-core/
  modules/tn-test-support/
  docs/contracts/core-v1.md
  docs/contracts/core-api-dump.txt
  fixtures/documents/v1-sample-plan.json
  .github/workflows/checks.yml
  docs/evidence/F01/cases.json
  docs/evidence/F01/report.md
Public API/schema changes:
  Frozen v1 core contracts in :tn-core (inkspace.core.{id,unit,limits,outcome,error,model,payload,mutation,read,ports,codec})
  Committed public API signature dump in docs/contracts/core-api-dump.txt
Commands and exit codes:
  rtk proxy ./gradlew :tn-core:test :tn-test-support:test -> exit 0
  rtk proxy ./gradlew ktlintCheck detekt -> exit 0
  rtk proxy ./gradlew jvmTestAll --rerun-tasks -> exit 0
  rtk proxy ./gradlew architectureCheck apiCheck requirementsCheck -> exit 0
  rtk proxy ./gradlew androidUnitTestAll lint assembleDebug -> exit 0
Device / API / firmware / ABI / GPU / build profile:
  Host JVM: Linux x86_64, OpenJDK 27 (compiled with JVM_17 bytecode target), Gradle 9.1.0, Kotlin 2.3.20
Fixture IDs and hashes:
  fixtures/documents/v1-sample-plan.json (v1 golden schema fixture)
  fixtures/workloads/manifest.json (seed 20261105)
Tests: passed / failed / skipped (with reasons):
  F01ContractTest (:tn-core:test): 5 passed (id_types, coordinate_roundtrip, limits, wire_rejects_nonfinite, payload_golden_roundtrip), 0 failed, 0 skipped
  TestSupportAndArchitectureTest (:tn-test-support:test): 4 passed (bounded_fake_ports_enforce_overflow, test_clock_advances_monotonically, fake_document_read_distinguishes_residency, negative_fixture_gradle_projects_rejected), 0 failed, 0 skipped
Measurements: raw path, p50/p95/p99, sample count:
  Coordinate transform roundtrip error: <= 1e-9 pt across all tested scales (1e-4..1e4), well within <= 0.01 pt gate.
Remaining limitations / failing acceptance criteria:
  None for F01.
Status: verified
```

---

## 1. Summary of Implemented Contracts (`:tn-core`)
- **Typed IDs (`inkspace.core.id`):** `DocumentId`, `SpaceId`, `PageId`, `LayerId`, `ObjectId`, `AssetId`, `VersionId`, `GestureId`, `UndoLabel`, `TransactionId`, `Revision`, `SessionEpoch`, `RequestGeneration`. All validate non-blank / non-negative invariants at construction.
- **Units & Camera (`inkspace.core.unit`):** `DocPoint`, `DocRect`, `ViewPoint`, `ViewPx`, `DocUnits`, `CameraTransform`. Enforces `isFinite()` and world coordinate envelope `<= 1e9 pt`, camera scale `1e-4..1e4`, and roundtrip accuracy `<= 0.01 pt`.
- **Payloads (`inkspace.core.payload`):** `StrokePayload`, `ShapePayload`, `ConnectorPayload`, `TextPayload`, `ImagePayload`, `GroupPayload`, `StickyPayload`, `TapePayload`, `LinkAnchorPayload`.
- **Mutations & Validation (`inkspace.core.mutation`):** `EditPlan`, `ValidatedPlan` (internal constructor), `ChangeSet`, `AppliedEdit`, `validatePlan` checking base revision, preconditions, object locks, residency, and `Limits`.
- **System Ports (`inkspace.core.ports`):** `GeometryPort`, `TextPort`, `ResourcePort`, `StoragePort`, `PdfPort`, `RenderPort`, `JobSink`.
- **Wire Codec (`inkspace.core.codec`):** CBOR and JSON serialization via `CoreCodec`, strictly rejecting `NaN`, `Infinity`, `-Infinity`, and unknown keys.

## 2. Test Support & Negative Architecture Verification (`:tn-test-support`)
- Bounded fake ports (`BoundedFakeStoragePort`, `BoundedFakePdfPort`, `BoundedFakeRenderPort`, `BoundedFakeJobSink`) enforcing queue capacity limits and returning `Outcome.Failure(SubmitError.QueueOverflow)`.
- `ArchitectureVerifier`: inspects workspace and temporary fixture Gradle projects, rejecting forbidden `api`, `implementation`, `testImplementation`, `debugImplementation` (variant), and `includeBuild` (composite-build) edges as well as prohibited `android.*` / `java.sql.*` imports inside `:tn-core`.
