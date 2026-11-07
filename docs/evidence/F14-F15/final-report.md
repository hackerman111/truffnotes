# F14–F15 implementation and validation

2026-11-07. Branch `fix/editor-through-f15`. This report supersedes the earlier partial-implementation report. Source specification: `truffnotes.md`; phase requirements: `docs/superpowers/plans/2026-11-05-truffnotes-android.md`.

## Implemented application behavior

- Android input reaches the shared ink engine, resolves the active workspace session, and uses stroke IDs unique across reopen. Tool/camera/surface changes discard drafts; global cancellation clears every custom pointer. Hardware eraser, ruler, shape, brush and Tape styles reach validated edit plans. Shared shape/Canvas replay covers shapes, arrowheads, connectors, images, Sticky and Tape.
- PDF import handles missing CropBox via MediaBox, retains the requested destination through picker recreation, and stages bounded streams. Native import validates hashes, quotas, graph/depth invariants and ID collisions; copy remaps references. Retry receipts complete native publication in the library after recreation/failure.
- Native archives, object bundles, templates and multi-document collections are wired to SAF and library UI. Collections publish their complete hierarchy in one atomic library operation; fresh document installations are compensated on failure, and interrupted publication receipts reconcile after restart. Optional manifest display names preserve duplicate names and names containing separators independently of safe archive paths. Template creation defaults to one selected/first page.
- Export UI offers full/page/range/region/selection scopes, chosen/visible layers, Sticky expansion and Tape reveal, with dependency/link/fallback preview. Changing options invalidates the preview. All-layer full-native output streams directly, including cold payloads. PNG and JPEG stream bounded strips; JPEG no longer requires a complete 2 MP bitmap. Actual PDF fallback warnings are shown after export.
- PDF composition preserves source forms/paths/text, crop/rotation and object affine transforms. Vector/flattened output uses shared CanvasReplay overlays; supported standard annotations are real Ink/Text/Square/Circle objects with appearances. Source links/outline targets and notebook links are remapped where possible. Unsupported actions/annotation/font/texture cases are explicitly reported. See `pdf-completion.md` for exact fallback/admission limits.
- Recovery acquisition defers that session's edits until the Store worker freezes its read generation and asset pins, keeping Main and the engine free of storage IO. Full native recovery streams without an internal temporary archive. Cold payloads merge from a WAL snapshot with version checks; scoped/visual exports admit selected-page payload memory before materialization. Pending applied asset references survive collection after commit failure. Timeout/interruption closes an unclaimed lease.
- Zoom uses the same document/session and freezes each stroke transform; resize/dock cancels incomplete strokes and retains the viewport center anchor. Toolbar orientation/dock/focus, independent palettes/presets, tool styles, gesture timings and palm posture are connected to persisted state. PDF search highlights/navigates results through the PDF/world/Zoom mapping. Laser remains transient. Timer/stopwatch/laps/history and countdown configuration persist; elapsed time uses monotonic Android time without duplicate workers. Background notifications are F20 scope.

## Verification

Final combined command completed successfully:

```
rtk proxy ./gradlew apiDump ktlintCheck detekt jvmTestAll architectureCheck apiCheck requirementsCheck androidUnitTestAll lint assembleDebug --continue --offline -Pkotlin.incremental=false --max-workers=1
```

258 host tests across 18 modules/application, zero failures/errors/skips. XML-derived counts and APK hash are in `test-summary.json`. `verification.log` contains the final successful command. Source fingerprints are in `source-sha256.txt`. Android lint, architecture/API and requirement-metadata checks passed. Repository ktlint/detekt tasks are lightweight local checks; metadata coverage is not device acceptance.

New behavioral coverage includes pending stroke/image/style recovery after injected ENOSPC, unchanged saved watermark, exact imported reopen, cold-record generation after later commits, cold native preview, memory admission, silent provider truncation receipt, independent JPEG decoding, atomic collection publication/names, deep group rejection, shape replay, PDF boxes/transforms, palette/orientation/reset, readonly Tape, tool return, resize anchor/cancellation and timer history. Historical F03–F07 generated benchmark evidence was restored, not overwritten. Existing renderer goldens and timing thresholds were retained.

`check_pdf_composition.py` passed after the final build: six backend-generated fixtures pass qpdf structure checks, Poppler and independent MuPDF rendering. Text remains extractable, paths remain vector, annotation objects have appearances and navigation targets resolve. These are backend fixtures, not device-generated Android PdfDocument exports.

Scoped independent reviews and fix dispositions: `f14-completion-review.md` and `completion-review.md`. All reported important findings were addressed; the last scoped review found no remaining important defect in those fixes. No commit, merge or push was performed.

APK: `app/build/outputs/apk/debug/app-debug.apk`

SHA-256: `f124a159ddd95db520cedc3f2acbaedc89d5d11ac61abbca6e2b2d1f0783ae17`

## Acceptance that still requires hardware

No Android device/emulator is connected. The implementation and host checks are complete for the changes described here; full F14/F15 phase gates are not marked passed. The plan explicitly requires real stylus Zoom/PDF/toolbar tests, Android IME and font/image output, actual SAF short-write/cancellation providers, real full-volume recovery, lifecycle/power-loss and device memory/latency measurements. Device steps are recorded in `f15-completion.md`; PDF limits and unsupported cases are recorded in `pdf-completion.md`. Host checks do not replace those measurements. Original plan phase checkboxes remain open for this reason.
