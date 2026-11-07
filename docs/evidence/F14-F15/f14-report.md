# F14 implementation evidence

Status: partial implementation; the F14 gate is NOT passed.

Implemented:

- New JVM `tn-exchange` module and `Exchange.start` native job API with cancellation, typed unsupported modes, shared Durable/Recovery reader contract and one encoder.
- Streaming ZIP writer/reader using `CoreCodec` CBOR, deterministic paths/order/timestamps, content SHA-256 manifest, revision/page/layer metadata, bounded object entries and streaming reachable assets. No internal temporary file during export.
- Import rejects traversal, duplicate/unknown names, expanded quotas, bad digests, missing assets, schema mismatch, directory mismatch and cancellation before transactional publication. Staging callbacks remain private until manifest validation.
- Validated core `restoreSnapshot` trust boundary checks graph IDs, layer/space ownership, payload digests, group ownership/cycles and internal targets.
- Explicit native-copy remapping of document/space/page/layer/object IDs and group/connector/sticky/link references; content-addressed assets are reused.
- `DocumentStore.installSnapshot` streams and verifies assets, creates a SQLite checkpoint in a private staging directory and moves the new directory into place, refusing existing document IDs. Reopening starts from exact imported applied revision and then replays subsequent transaction log entries. Existing store documents without a native checkpoint retain the original replay path.
- `AndroidNativeExchange` imports SAF streams, stages at most 128 MiB expanded archive input, installs the restored document, and opens only fully matching existing archive generations. Explicit copy IDs select remapping. Native export streams directly to a newly created SAF URI.
- `SafeExport` rejects nonatomic overwrite before opening; exceptions from write/flush/close abort an incomplete new output. Android SAF output deletes its fresh URI on failure.
- Streaming RGBA PNG encoder takes bounded raster strips (maximum 262,144 pixels) and emits incremental IDAT chunks. Android image helper uses existing Snapshot rendering; JPEG uses a bounded Android bitmap up to 2 MP.
- `AndroidPdfWriter` owns one Android PdfDocument page at a time; vector helper reuses existing PdfOverlay/CanvasReplay/GeometryPort/TextPort for strokes and shaped text.
- Portable format contract in `docs/contracts/inknote-v1.md`.
- Editable native selections for full document, explicit pages, inclusive range, one-page region and object IDs, with chosen-layer/visible-layer filters. Dependency closure retains complete groups, connector endpoints and Sticky attachments. Omitted-page links are explicitly reported and detached. Original source snapshot remains unchanged.
- `NativeBundles.write` emits the same editable native format for reusable object bundles, object collections and one-page templates, with an explicit purpose in the header. Template defaults to the first page and accepts an explicit alternative page selection, including PDF-backed page references/assets.
- `NativeCollection.write/read` streams deterministic nested `.inknote` documents with preserved logical library paths, nested and envelope SHA-256 validation, aggregate expanded quotas, cancellation and collection-level transactional staging.

Observed tests and builds:

- RED: `:tn-exchange:compileTestKotlin` failed on the test-first missing `StreamingPng`; parent also captured this in `/tmp/truffnotes-input-red.log`.
- Behavioral RED: checkpoint import test failed with `Native import failed: EditPlan must contain at least one mutation`. The implementation was corrected to call the shared SQL object synchronizer directly rather than manufacturing a mutation plan.
- GREEN: `:tn-exchange:test`: 10 archive tests plus 1 PNG exact-pixel/strip-bound test. Cases include native header round-trip, frozen pending image/style source, traversal, expanded quota, bad hash, unsupported schema, canceled import, nonatomic overwrite rejection, write exception abort and ID collision policy.
- Subsequent test-first RED for missing selection/bundle types was captured by parent in `/tmp/truffnotes-f15-build.log`. GREEN after implementation: `:tn-exchange:test`, 21 tests (11 above + 6 selection and 4 collection tests). Collection coverage includes deterministic bytes/hierarchy, bad outer hash abort, traversal path rejection, aggregate quota and cancellation. Selection coverage includes source immutability, layer policy, group closure, omitted-page link report, region whole-object behavior, default template page and invalid scopes.
- Final GREEN: `:tn-exchange:test --offline --max-workers=2 -Pkotlin.incremental=false`, 24 tests (10 archive, 1 PNG, 9 selection, 4 collection), zero failures. This includes subset opens only reachable assets, single member closes its parent group and explicit template page selection. Cumulative nested-ZIP writer quota was also tightened to match the reader.
- GREEN: checkpoint exact revision 7 reopen and no overwrite test, before the final test additions below.
- GREEN: `:tn-runtime:compileDebugKotlin`, including Android native/image/PDF helpers, and `:tn-pdf:compileDebugKotlin`.
- Final test additions awaiting parent rerun: imported checkpoint followed by commit revision 8/reopen; failed asset-digest install leaves no document/staging directory.
- Parent-requested app input regression tests were run and failed: old session wins instead of new, and input stroke never reaches the expected editor state. Parent owns fixes; these are not F14 archive failures.

Remaining gates and scope limits:

- No existing bounded disk-backed SnapshotReader or RecoveryLease existed in the baseline. The resident adapter refuses nonresident objects. Parent must freeze/pin the Core revision and pending asset references through export completion; full disk-full pending stroke/image/style recovery plus unchanged saved-watermark integration has not been demonstrated.
- Parent must finish application wiring, library entry publication after successful native import, picker/new-URI flows and worker ownership. `AndroidNativeExchange` is actual import/install/export implementation but not by itself UI wiring.
- Native scope/bundle/collection APIs now have JVM fixtures. Parent UI still needs to expose selection/report preview, original-ID collision/copy choices and multi-document library publication. Native region selection retains whole editable objects and dependencies rather than destructive clipping; that policy must be shown before export. Collection sinks must stage child commits until final envelope validation and implement atomic library publication/rollback.
- PNG strips are bounded; JPEG is bounded whole-bitmap encoding, not streaming tiled JPEG. No device/SAF provider tests ran. A provider silently discarding output despite successful OutputStream writes needs an external verification receipt.
- Vector PDF currently supports strokes/text on pages without source PDF/template assets. Shape/image/Sticky/Tape vector output is explicitly rejected because existing Android Canvas replay lacks those implementations. Source PDF merge, flattening, actual standard PDF annotation objects, PDF link/outline preservation, fallback preview/report and structural/two-independent-viewer gates remain missing. These modes must not be presented as complete.
- Sticky/Tape and chosen-layer appearance policies are not yet an immutable export lease/preview UI. Native export retains all editable content including Tape-covered data and does not claim redaction.
- Native import is resident restore after bounded staging, not a lazy/disk-backed 100k-object import path. Asset IDs installed into the existing store must be `sha256:<content hash>`.
- Store directory publication uses a same-parent `Files.move` with no replace option and existing-ID checks. Full crash/power-loss and concurrent publication proofs, directory fsync and device fault schedules remain untested.

No commit was made. No device was connected, so Android runtime behavior, PDF structural validity and two-viewer compatibility remain unverified.

## Parent integration follow-up (supersedes earlier parent TODOs)

SAF pickers, new-output names, native library publication/open, explicit copy choice and per-scope native exports are connected. A pinned resident-model reservation holds the captured applied snapshot through export. Library publication runs in one NonCancellable IO operation and retry receipts survive interrupted/failed library writes; receipt reconciliation runs on Activity creation. Directory fsync/power-loss schedules are still untested.

Shared CanvasReplay now supports shapes, images, Sticky and Tape. Vector PDF accepts these supported objects; source PDF/template composition and connectors remain rejected. Appearance PDF is a bounded tiled 144-dpi raster fallback, explicitly labelled in UI; it accepts multiple pages. This fallback does not satisfy the plan's source-PDF vector preservation requirement. Source tiles use frozen layer opacity and strict admission during export: any required working-set/cache failure aborts the output.

Original-ID native collisions and expanded resident import bounds remain as documented. Multi-document archive API exists, but transactional collection library publication/UI, selection report preview, and template registration are not complete.
