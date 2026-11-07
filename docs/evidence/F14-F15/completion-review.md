# F14/F15 final completion review

What this change does: F14 now exports a frozen applied generation through streaming native/image writers and source-preserving PDF composition. F15 adds persisted timer/laps/history, palm posture controls, PDF hit navigation and draft cancellation/center preservation during viewport resize.

2026-11-07. Source review only; implementation workers have finished and the parent is running final validation. No source edits, subagents or duplicate test runs. Read the F14/F15 completion evidence, final recovery/export implementations, export preview/caller UI, timer state/persistence, palm classification, PDF query/highlight and surface attachment code.

## Must fix

1. **Cold-document export is blocked by the required UI preview, and default full native takes the materializing route** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/WorkspaceGateway.kt:172`, `modules/tn-ui/src/main/kotlin/inkspace/ui/WorkspaceControls.kt:395`, `modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidDocumentExport.kt:17`).
   - Problem: `previewExport` always calls `NativeSelection.project` on `editorState().snapshot`; NativeSelection requires every object resident. The UI enables Choose output only after successful preview. A supported mixed resident/cold snapshot therefore cannot reach any export through the panel, even though RecoveryLease now supports it. In addition, the panel always supplies a nonnull set of all layer IDs by default. `fullNative` requires null layer IDs, so default full native export unnecessarily materializes all records and can fail memory admission instead of streaming.
   - Fix: Make preview compatible with a frozen bounded reader/selected record admission, and normalize an unchanged all-layer selection to null (or recognize that equivalent set in the full-native decision). A cold full-native preview should not require loading every payload.
   - Consequence: The advertised recovery path is inaccessible from the UI for cold snapshots; documents that could stream successfully can instead fail on resident memory budget. Current store open still loads all records, so this is the cold-representation activation boundary rather than an observed default-session failure.

2. **Changed export settings retain a successful preview for different settings** (`modules/tn-ui/src/main/kotlin/inkspace/ui/WorkspaceControls.kt:369`, `modules/tn-ui/src/main/kotlin/inkspace/ui/WorkspaceControls.kt:401`).
   - Problem: `preview` is a plain remembered string and is never invalidated when mode, scope, pages, layer selection, Sticky/Tape policy or region changes. After previewing one page with Tape hidden, the user can switch to full output or Tape revealed and immediately Choose output while the displayed preview still describes the old selection. An older asynchronous preview can also arrive after newer settings have been entered.
   - Fix: Bind the successful preview to the exact normalized ExportOptions and captured selection/document revision; enable output only when the current request matches. Ignore stale asynchronous preview results.
   - Consequence: The explicit review step can describe different pages/layers/visibility from the exported file, including covered content becoming visible.

## Resolved previous findings

The PDF template source now uses translated full-page placement for region crops. Cold records are materialized with selected-page filtering, count/version checks and incremental memory admission. The runtime freezes that session's queued mutation processing while storage captures its durable reader and asset pins; pending write assets survive failed commits. Acquisition timeout/interruption cancels ownership transfer, closes any later unclaimed lease, and closes a concurrently completed lease through the fallback continuation. The previous timeout leak is resolved by inspection.

## F15 result and acceptance boundary

No additional confirmed important F15 implementation defect was found in the inspected timer, palm posture, PDF hit mapping or resize paths. The timer uses a persisted monotonic origin with boot identity, bounded laps/history and no duplicate scheduled clock. PDF hit boxes use source/local/object transforms; highlight/centering changes view state, and resize cancels drafts while preserving the center anchor.

Verdict: address findings 1–2 before declaring the new export preview/cold recovery UI complete. This review does not independently certify the parent's running validation.

Android device acceptance remains open separately: real stylus/palm behavior, Android font/image/handwriting PDF fidelity, process-kill timer persistence, actual SAF short writes/cancellation, input/display alignment and memory measurements. Existing host fixtures and two-viewer PDF backend evidence do not substitute for those measurements; their absence is not itself a confirmed code defect.

## Final scoped disposition after UI fixes

Both remaining findings are resolved by source inspection. ExportPanel normalizes all-layer selection to null, preserving the streaming full-native route. Full-native preview reads immutable metadata without projecting resident records; other cold previews acquire a recovery lease and admit selected-page payload bytes before projection. Their leases and memory reservations close in finally blocks.

Choose output now requires equality between the current normalized ExportOptions and the options that produced the successful preview, including object IDs and output policy. An asynchronous result for different options cannot enable output for the current request. The previous alternate quick-export entry points were removed. Export warnings are returned to the activity and displayed; optional private report persistence cannot turn a completed export into a reported failure.

Final scoped verdict: no remaining actionable important defect in these reviewed fixes. Previous findings are retained above as review history, not open issues. No new tests or broad review were performed in this disposition; the parent owns final validation and the device acceptance boundary remains open.
