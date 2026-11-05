# F00 Evidence Report: Android Stack Verification & Target Freeze

```text
Phase / task / Requirement IDs: F00 (F00.1, F00.2, F00.3, F00.4, F00.5) / R01, R34, R36
Revision or source fingerprint:
  truffnotes.md: 312c200fcac56e2f418bec61d4d8eee2ef12f8894d530352b94bb6e465a8566a
  goodnotes_red.md: 5eb666b3806238aaa8948346dd4a9a064bbc58a5ea6efcb18353d1dcd02191ad
  images.zip: 3abc92bef98d6414709d8f8432f6d348e08fbc511341465a9b1a8088fcaa0f6a
Changed paths:
  docs/adr/0001-stack.md
  docs/perf/devices.json
  docs/perf/protocol.json
  requirements.json
  fixtures/workloads/manifest.json
  tools/spikes/check_manifest.py
  tools/spikes/android/
  docs/evidence/F00/cases.json
  docs/evidence/F00/report.md
Public API/schema changes: None (isolated spike ports and data models in inkspace.spike.*)
Commands and exit codes:
  rtk proxy python3 tools/spikes/check_manifest.py -> exit 0
  readelf -W -l <extracted .so> -> exit 0 (16 KiB / 0x4000 LOAD alignment verified)
  tools/spikes/android: ./gradlew testDebugUnitTest -> exit 0
Device / API / firmware / ABI / GPU / build profile:
  Host: Linux x86_64, OpenJDK 27, Android SDK Build-Tools 35/36, Platform 35
  ABIs: arm64-v8a (production), x86_64 (unit/emulator test)
  Target Profiles: D1 (S Pen 120Hz), D2 (Active stylus 60/90Hz), D3 (4GiB RAM 60Hz), E29, E33, E35_16K
Fixture IDs and hashes:
  W0 (blank_a4), W1 (notebook_100p), W2 (dense_500p), W3 (pdf_1000p), W4 (canvas_stress), W5 (library_10k), W6 (endurance_30m)
  Manifest generator seed: 20261105
Tests: passed / failed / skipped (with reasons):
  StackVerificationTest: passed (sample ordering, ACTION_CANCEL purge, SurfaceEpoch teardown, quantiles, text layout plan)
  SpikeSurfacePdfInstrumentedTest: skipped on host (requires attached Android device or running emulator)
Measurements: raw path, p50/p95/p99, sample count:
  Quantile calibration verified via tools/spikes/check_manifest.py using nearest-rank formula
  Physical hardware traces: pending_hardware_attachment
Remaining limitations / failing acceptance criteria:
  Physical hardware measurements (D1, D2, D3, high-speed camera pen-to-photon) require attached physical hardware. Per plan gate rules, the F00 hardware gate remains open awaiting device connection.
Status: verified (host toolchain, 16 KiB alignment, manifest, spike JVM tests); hardware gate open pending device attachment.
```

---

## 1. Inventory & Specification Fingerprints
- `truffnotes.md`: SHA-256 `312c200fcac56e2f418bec61d4d8eee2ef12f8894d530352b94bb6e465a8566a`
- `goodnotes_red.md`: SHA-256 `5eb666b3806238aaa8948346dd4a9a064bbc58a5ea6efcb18353d1dcd02191ad`
- `images.zip`: SHA-256 `3abc92bef98d6414709d8f8432f6d348e08fbc511341465a9b1a8088fcaa0f6a` (23 files)
- Original specifications are preserved unmodified in their initial read-only state.

## 2. Native Library 16 KiB Page Size Alignment Verification
Native shared libraries verified using `readelf -W -l`:
1. `androidx.sqlite:sqlite-bundled-android:2.7.1`
   - `jni/arm64-v8a/libsqliteJni.so`: LOAD segments have `Align 0x4000` (16 KiB).
2. `io.legere:pdfiumandroid-core:2.0.0`
   - `jni/arm64-v8a/libpdfium.so`: LOAD segments have `Align 0x4000` (16 KiB).
   - `jni/arm64-v8a/libpdfiumandroid.so`: LOAD segments have `Align 0x4000` (16 KiB).
   - `jni/x86_64/libpdfium.so`: LOAD segments have `Align 0x4000` (16 KiB).
Both libraries are compatible with Android 15 16 KiB page size devices.

## 3. Workload Corpora & Performance Protocol
- `docs/perf/devices.json`: specifies profiles D1, D2, D3 and emulators E29, E33, E35_16K.
- `docs/perf/protocol.json`: defines exact nearest-rank quantile formula $\lceil N \times p \rceil$, warmups, sample bounds, memory PSS caps, and fault injection criteria.
- `fixtures/workloads/manifest.json`: documents synthetic workloads W0 through W6 with seed `20261105`.
- `tools/spikes/check_manifest.py`: verified all requirements, acceptance criteria, workloads, and quantile formulas. Executed with exit code 0.

## 4. Spike Implementation & Tests
- Located in `tools/spikes/android`:
  - `SpikeSurfaceView`: renders via hardware Canvas (`lockHardwareCanvas`), handles `MotionEvent` historical batches without object allocations, handles `ACTION_CANCEL` / `FLAG_CANCELED` by discarding uncommitted draft.
  - `SurfaceEpoch`: monotonic generation counter to discard late renders after surface destruction or recreation.
  - `PdfiumSpikeAdapter`: interface for PDFium tile rendering, text search, outline, page import/export, and Android `PdfDocument` vector generation for multilingual text.
  - `StackVerificationTest`: automated JVM tests validating math, lifecycle, batching, and text paint plans.
