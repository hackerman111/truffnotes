# F14 completion review

What this change does: It adds recovery snapshot readers, streaming image output, collection publication and source-preserving PDF composition through shared Canvas replay. The PDF evidence demonstrates host backend fixtures, structural checks and two independent host viewers.

Review date: 2026-11-07. Read-only source review; only this report was written. Reviewed `/tmp/f14-completion-review.diff`, the F14 brief, PDF completion evidence, recovery test, store snapshot/pinning, collection/library publication, document export, JPEG/output receipt, PDF writer and replay callers. Existing passed suites were not rerun. Assumed load is the specification's large documents with cold payloads and editing during export.

## Must fix

1. **Region export rescales a PDF template instead of cropping it** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidPageExporter.kt:42`).
   - What this is: A page's asset-backed template is composed as a source form.
   - Problem: The template branch always places the whole source at `(0,0)` with `bounds.width/bounds.height`. Exporting a small region therefore squeezes the complete template into that region, while foreground objects are cropped in document coordinates. The adjacent pdfBackground branch correctly translates a full-page source.
   - Fix: Use the same translated full-page placement for template assets, including the region origin.
   - If skipped: Region/selection PDFs show a background that no longer lines up with their writing. Add a template-plus-offset-region fixture; existing crop/rotation backend fixtures do not exercise this caller.

2. **Cold objects disappear from visual export and scoped native export cannot handle them** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidNotebookFeatures.kt:322`, `modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidDocumentExport.kt:21`).
   - What this is: RecoveryLease can merge resident and durable cold records, but visual export rebuilds a snapshot from `allResidentObjects()` and scoped native export receives `lease.snapshot` instead of its complete records.
   - Problem: Given a snapshot with a `DirectoryEntry.NonResident`, full visual export silently omits that object; selection/native projection instead rejects it through NativeSelection's fully-resident requirement. Full native archive export correctly consumes the lease. The current store open path loads all records, and no active runtime eviction path was found, so this is a concrete cold-snapshot contract gap rather than a demonstrated default-session regression.
   - Fix: Make every export projection consume the frozen reader, loading only required pages/objects with bounded admission; do not discard cold records or materialize a whole large document without a limit.
   - If skipped: The advertised bounded snapshot/scopes contract fails once the model uses its supported cold representation. Add a mixed resident/cold export fixture that checks IDs and appearance.

3. **Snapshot capture and durable/asset pin acquisition are separated by a race** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/Runtime.kt:644`, `modules/tn-runtime/src/main/kotlin/inkspace/runtime/RecoveryLease.kt:17`).
   - What this is: The engine captures the applied snapshot and returns; the caller subsequently opens a durable snapshot and pins its assets.
   - Problem: Editing/save/collection can run in that gap. For a cold object, a later durable record can be merged into an older applied revision because the base generation is not tied to the captured snapshot. For an otherwise resident snapshot, removing its image and collecting the now-unreferenced asset in the gap can make pin acquisition fail. The memory governor reservation pins model memory, not storage assets. The recovery test collects only after lease acquisition and does not test this interval.
   - Fix: Capture the durable read generation and pin all captured-generation assets inside the serialized acquisition operation, before returning control to edits/GC. Preserve a consistent store/engine lock order.
   - If skipped: Export during editing can fail despite having captured a valid revision; cold export can carry newer records under an older revision label.

## Reviewed without an additional finding

LibraryService builds a private entry map before catalog commit, so early hierarchy validation failure does not partially publish the batch. Collection staging installs fresh UUID documents and publication recovery handles complete/partial crash states. OutputReceipt streams read-back verification after close; JPEG holds eight source rows rather than a full raster. PDF forms, annotation appearances and affine navigation placement use the shared composition route. No additional confirmed defect was found in those inspected paths.

## Acceptance boundary

Verdict: fix 1–3 before claiming complete F14 support for all documented scopes and frozen/cold snapshots. Item 2 has a conditional activation boundary stated above. No new dynamic reproduction was run; findings follow directly from the inspected caller data flow.

The supplied PDF evidence supports the host composition backend, not physical Android output fidelity or SAF behavior. Real Android PdfDocument handwriting/font/image rendering, Sticky/Tape policy combinations, provider short writes/cancellation and Android memory/device measurements remain acceptance work. Their absence is not itself proof of an implementation defect. The host recovery test demonstrates pending stroke/image/style export after injected commit failure and unchanged durable watermark, but does not demonstrate an actual full device volume and alternate SAF destination.

## Scoped fix rereview

The original findings above describe the pre-fix implementation. Rereview was limited to those three findings; no full suites were rerun.

- Finding 1 resolved by using translated full-page placement for template forms, matching pdfBackground placement.
- Finding 2 resolved for correctness by materializing RecoveryLease records with incremental cold-payload admission before scoped/visual export. The materializer verifies total count and cold record versions. Large scoped exports can now fail admission explicitly rather than silently omit objects; page-only loading remains a potential performance improvement, not a remaining silent-loss finding.
- Finding 3's revision/GC race is addressed by pausing that session's queued mutation processing until store-worker lease capture completes, capturing the durable transaction before unfreezing, checking the durable generation and cold object versions, and retaining dispatched pending-write asset references across commit failure. The updated test exercises cold-record stability after a later commit, cold-byte admission and pending-asset collection before/after lease acquisition.

One remaining issue in the revised acquisition lifecycle: **P2 — timeout/interruption abandons a future lease** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/Runtime.kt:676`). `result.get(30, TimeUnit.SECONDS)` can throw while the store-worker acquisition remains queued/running. When it later succeeds, `result.complete(lease)` stores a lease that no caller receives; the caller's `use` block was never entered. This leaks memory reservations and asset pins, and a cold lease additionally holds a WAL read transaction. A slow store job or caller interruption is sufficient. Cancel/abandon the result on failed waiting and close the produced lease when ownership cannot be transferred, including the completion-versus-timeout race. Verify with a delayed acquisition and assert that reservations/pins are released after timeout.

Current scoped verdict: findings 1 and 2 resolved; finding 3's original race resolved, but its new timeout/interruption ownership leak requires correction before declaring this acquisition path complete. Android acceptance boundaries above remain unchanged.
