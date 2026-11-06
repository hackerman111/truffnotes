# F13 PDF backend, import and reading

Phase / task / Requirement IDs: F13.1-F13.5 implementation; R21/R27/R33.

Changed paths: `modules/tn-pdf`, additive `tn-core` PDF contracts/PdfRequest/PdfObjectPayload, `modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidPdfWorkspace.kt`; runtime/renderer wiring is integrated by the parent executor.

Public API/schema: additive Open/Info/Tile/Text/Search/Links/Outline/Close requests; immutable source PdfPageRef/PdfObjectPayload with frozen localBounds; bounded replies. Text uses native character startIndex/nextIndex for paging and spans expose UTF16 cursor offsets. Source PDF bytes are never rewritten by document edits.

Implemented:

- Bundled PDFium 2.0.0 instantiated only by the isolated, non-exported PdfIsolatedService; native documents/pages/text/finder handles confined to one worker thread, close order text/finder then page then document then descriptor. Client death releases document handles.
- App-owned AndroidPdfPort implements core PdfPort, admission caps, single owner callback thread, service restart generation invalidation and rebinding. Cancellation suppresses stale callbacks; already-running native work finishes on its owner and canceled open results are closed.
- Messenger IPC uses capped JSON metadata and read-protected SharedMemory for raster buffers. FD ownership transfers to service on open and all failure/response paths close local resources. Requests and responses are validated at both boundaries.
- Streamed nonseekable SAF/receive-intent inputs, 256 MiB source cap, 64 KiB transfer buffer, immutable SHA asset publication through the existing stager. Permission/revocation/provider failures become typed recoverable errors.
- Page info available without rendering other pages. Crop/rotation transforms, viewport raster requests, glyph bounds/Unicode extraction with bounded pagination, existing-text search, links and outline. Scan PDFs return no text; no OCR is used.
- PdfImportPlanner supports page ranges, fresh-document replacement, locked background references and editable on-canvas PDF objects. All references are validated before returning one EditPlan; ordinary annotation objects remain independent and undo uses the existing reducer.
- AndroidPdfWorkspace integrates staged assets and SubmitEdit, native await operations on the existing bounded worker, PDF text/search/links/outline helpers, transformed viewport tiles, governor reservations and a 16 MiB raster LRU. Visible resources share a bounded pixel budget; zooming requests new resolution.

Commands and exit codes:

- Initial `rtk proxy ./gradlew :tn-pdf:test --offline` RED -> exit 1, expected missing foundation implementation.
- Parent `/tmp/f09-13-full-green1.log` -> exit 1 overall, expected PdfImportPlanner/PdfImportAction RED before import implementation; actual service and Android instrumentation source compilation succeeded in that run.
- Parent `/tmp/f09-13-integration-red.log` -> exit 1 overall from another module; `:tn-pdf:testDebugUnitTest` passed. XML reports show F13PdfImportTest 2, F13PdfSessionTest 2, F13PdfSafetyTest 3, all zero failures/errors.
- `rtk proxy python3 modules/tn-pdf/fixtures/check.py` -> exit 0: all nine frozen hashes and host-parsed metadata/text/link targets match; malformed fixture rejected. This verifies corpus integrity with host PyMuPDF, not Android PDFium behavior.
- Subsequent complete serial verification is recorded by the parent executor; inspect its final run before claiming full build success.

Fixture IDs/hashes: nine real synthetic PDF files in `modules/tn-pdf/src/androidTest/assets/pdf/manifest.json`, covering text/vector, scan, encrypted, rotated crop, damaged/xref, huge dimensions, missing font, internal links and named destinations/outline. The manifest stores exact SHA-256 values. Generator `modules/tn-pdf/fixtures/generate.py` uses host PyMuPDF; encrypted fixture generation uses encryption entropy, so frozen files and manifest hashes are the evidence rather than byte-identical regeneration.

Tests: seven host JVM tests passed in the parent integration run. Native service instrumentation code covers open/info/tile/text/search/links/outline/close, original SHA preservation, scan/damaged source, password failures, rotated crop, missing font and huge dimensions. Native instrumentation execution must be confirmed separately; compilation is not device evidence.

Device / API / firmware / ABI / GPU / build profile: host compilation and JVM tests. No physical device performance evidence recorded here.

Measurements: no measured W3 first viewport/page jump/PSS/native memory results.

Remaining verification:

- F00 PDF/Surface instrumentation remains unverified; its existence does not prohibit writing the real backend, but full F13 gate PASS still requires actual device execution.
- Actual native raster/text coordinate correctness, encrypted/malformed behavior, service death/restart during native work and OEM/device native memory peaks require instrumentation/stress traces. Native calls cannot be forcibly interrupted safely; cancellation discards results and closes resources on the owner thread.
- Android IME and notebook UI/performance gates are covered by their owning phases, not by these PDF JVM tests.
- PDF writer/export is F14.

Status: full backend/import/reading implementation and integration supplied; host tests passed; Android and performance gates await actual execution.
