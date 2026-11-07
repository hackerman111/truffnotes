What this change does: The patch connects Android input and toolbar actions to the editor runtime, adds native archive import/export and checkpoint restore, and adds Zoom, tool settings, PDF reading and initial accessories. This review follows the uncommitted tree and `/tmp/truffnotes-f15-review.diff`, with `truffnotes.md` and the approved Android plan as the specification.

Review snapshot: 2026-11-07, before the parent agent's follow-up repairs. Locations below refer to this snapshot. Expected load is one Android tablet user, including untrusted imported documents and concurrent editing/export. No tests were rerun and no application code was changed during review.

## Important / must fix

1. **Native import can install a document that never enters the library** (`app/src/main/kotlin/inkspace/app/MainActivity.kt:65-78`).
   - **What this is:** Native import first publishes the private document directory inside an IO block, then creates its library entry in a second block owned by the Activity.
   - **Problem:** Rotate or recreate the Activity while a sufficiently large native import is running. The blocking `importNative` call can finish installation after `lifecycleScope` is canceled, but cancellation at the `withContext` return prevents `LibraryAction.Create`. A later original-ID import can report `openedExisting=true` and skip library creation again, so this document remains inaccessible from the library. A library write failure after installation has the same split-publication problem.
   - **Fix:** Give install plus library publication one application-owned operation and a recoverable publication record, or compensate failed publication by removing only that operation's new document. Reconcile an already installed matching document with a missing library entry. The Activity should observe completion.
   - **If we skip it:** Successful import work can leave hidden stored documents and retries cannot reliably recover them. Add recreation-during-install and publication-failure cases.

2. **Source PDF content can silently disappear from a successful image/PDF export** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidNotebookFeatures.kt:323-340`; `modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidPdfWorkspace.kt:220-232`).
   - **What this is:** PNG/JPEG and appearance-PDF exports request PDF tiles through the screen rendering helper.
   - **Problem:** The helper skips a source PDF object when its PDF working-set reservation fails, or when the tile cache reservation fails. Returning an empty/partial tile list is acceptable for a later screen refresh, but the new exporter renders it permanently and reports success. A PDF-backed page exported under memory pressure therefore has blank/missing source content.
   - **Fix:** Add a strict export tile path that either obtains every required tile or fails the export. Do not turn memory admission failure into a successful partial image. Cover failure of both reservations with an export test.
   - **If we skip it:** The user can share a corrupted-looking exported document without receiving an error.

3. **PDF exports can mix layer opacity from different revisions** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidPdfWorkspace.kt:267`; new caller `AndroidNotebookFeatures.kt:335`).
   - **What this is:** The exporter passes a frozen snapshot to `pdf.tiles`, but `buildTile` reads layer opacity from the current runtime snapshot.
   - **Problem:** Start a long PNG/appearance-PDF export, then change the source PDF layer opacity. Later strips use the new opacity while earlier strips and other object properties use the captured revision. Closing the session can instead replace its opacity with the fallback `1f`.
   - **Fix:** Pass the layer opacity from the snapshot supplied to `tiles` into `buildTile`; remove live document reads from this export dependency. Test an opacity edit between strip renders.
   - **If we skip it:** The reported exported revision does not describe the resulting appearance.

4. **Flattened PDF export rejects every multipage notebook** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/AndroidPageExporter.kt:29`).
   - **What this is:** The UI offers `FlattenedPdf` and `exportPages` passes every notebook page to PDF modes.
   - **Problem:** The guard allows multiple pages only for `VectorPdf`, so a normal two-page notebook fails with “Choose one page for image output” before reaching the implemented flattened-PDF page loop.
   - **Fix:** Apply the single-page restriction only to PNG/JPEG. Exercise a two-page appearance PDF and inspect both pages.
   - **If we skip it:** A displayed export command always fails for the common multipage case.

5. **Native restore bypasses the existing group-depth limit** (`modules/tn-core/src/main/kotlin/inkspace/core/model/SnapshotRestore.kt:34-40`).
   - **What this is:** Native import validates acyclic group ownership but does not enforce `Limits.maxGroupDepth` (default 32).
   - **Problem:** An archive with a valid hash and a chain of thousands of single-child groups fits the archive quotas and is accepted. Validation walks the full ancestor chain for every member, producing quadratic work. Later group expansion recursively visits these groups in `modules/tn-objects/src/main/kotlin/inkspace/objects/Group.kt:80-103`, allowing stack exhaustion on ordinary selection/edit operations. Such a model cannot be constructed through the normal group planner because it checks the limit.
   - **Fix:** Enforce the same depth limit at restore, with a bounded traversal, before publication. Add a validly hashed archive with depth 33 and a long hostile chain to rejection tests.
   - **If we skip it:** An imported file can stall import or crash the editor during subsequent interaction.

6. **Two/three-finger undo and redo never reach their gesture recognizer** (`app/src/main/kotlin/inkspace/app/CanvasInputRouter.kt:39-58`; controls at `modules/tn-ui/src/main/kotlin/inkspace/ui/WorkspaceControls.kt:189-190`).
   - **What this is:** The new router reserves finger streams for camera navigation and exposes enabled undo/redo gesture controls.
   - **Problem:** With stylus-only enabled, every finger event stays in navigation. With finger drawing enabled, the second finger sends CANCEL and switches to navigation. In both cases the completed two/three-finger tap is swallowed, and the runtime receives no gesture or explicit Undo/Redo command.
   - **Fix:** Recognize bounded multi-finger taps in the router before committing to navigation, honoring the configured distance, time and enable settings; send exactly one undo/redo command. Keep pan/pinch streams out of ink. Add router-level tap-versus-pinch tests.
   - **If we skip it:** Both advertised settings have no effect in the actual Android canvas.

7. **Global cancellation only removes one non-ink tool pointer** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/Runtime.kt:999`; caller `app/src/main/kotlin/inkspace/app/TruffnotesAppContainer.kt:179-183`).
   - **What this is:** Shape/lasso/eraser/Tape paths bypass InkEngine and have their own cancellation branch.
   - **Problem:** Android ACTION_CANCEL is global, and `cancelDrawing` also emits a global CANCEL with pointer ID 0. This branch removes only `batch.pointerId`, so a Zoom/surface cancellation while pointer 5 is drawing a shape retains its path. An ensuing terminal event can still commit the canceled path; multiple active pointers also leave all but one path behind. InkEngine already distinguishes global CANCEL from per-pointer FLAG_CANCELED correctly.
   - **Fix:** Clear all tool paths and their overlays for global CANCEL, retaining per-pointer removal only for FLAG_CANCELED. Test a nonzero pointer and two simultaneous pointers through cancellation, then verify no late UP creates an edit.
   - **If we skip it:** The cancellation guarantee applies to pen strokes but not the other editing tools.

8. **Tape style controls write settings that insertion ignores** (`modules/tn-runtime/src/main/kotlin/inkspace/runtime/Runtime.kt:1035`; settings writer `modules/tn-ui/src/main/kotlin/inkspace/ui/WorkspaceControls.kt:97-104`).
   - **What this is:** Tool styles save color, width and opacity independently in `settings.tools[tool]`.
   - **Problem:** Select Tape, choose a new color and width, and draw. The insertion still uses legacy `penColor`/`penWidthPt` and the default Tape opacity, so the new settings do not change the result. The width is also forced to at least 12 pt despite the offered range.
   - **Fix:** Build TapePayload from `configFor("tape")` and make the displayed width range agree with the supported width. Wire the applicable style settings or omit unsupported controls with a concrete explanation.
   - **If we skip it:** A visible tool configuration screen promises changes that are not applied to the document.

9. **Current-page PDF/image import intent is lost on Activity recreation** (`app/src/main/kotlin/inkspace/app/MainActivity.kt:39,132-136,212-218,311-317`).
   - **What this is:** The new in-editor PDF import launches the picker with `importAsDocument=false`, but that field is not saved/restored.
   - **Problem:** Start “Import PDF” in an existing notebook and rotate while the system picker is open. The recreated Activity resets `importAsDocument` to true. The delivered result creates a separate notebook instead of inserting into the chosen one. During a slow staging copy, switching notebooks also redirects the final import because `perform` resolves the active session only after the copy.
   - **Fix:** Persist one pending import request including mode and target document/session, and submit against that captured target or reject its stale epoch. Include picker recreation and tab-switch-during-staging cases.
   - **If we skip it:** Import writes to a different location from the one the user selected.

## Build observation

At inspection time `app/src/main/kotlin/inkspace/app/CanvasInputRouter.kt:18-19` referenced an undefined `event` inside `onScale`. This was sent to the parent immediately. The parent confirmed the combined checks currently fail and is handling compile/lint repairs. It is a release blocker until the final verification rerun passes, not an additional runtime finding.

## Scope and deliberately unjudged behavior

- The F14/F15 reports explicitly mark their phase gates incomplete. Missing standard PDF annotation writing, source-PDF vector merge, links/outline preservation, full recovery/disk-full evidence, collection publication UI and real-device gates are requirements gaps already recorded by the implementation, rather than newly discovered regressions. The patch must not be presented as completed through F15 while these remain.
- ZIP traversal/duplicate names/hash/expanded byte checks, fresh SAF output policy, checkpoint no-overwrite and UUID stroke namespace were inspected. This was source review, not malicious-file fuzzing, filesystem crash testing, SAF-provider verification or device execution.
- Native export's page/selection scope is read after snapshot capture (`MainActivity.kt:102-107`), and selection comes from whichever notebook is currently active. Freeze scope and target together before worker dispatch; no complete UI scheduling reproduction was run, so this is recorded as a follow-up edge case rather than another ranked finding.
- The resident-only import memory ceiling and JPEG bitmap ceiling are disclosed implementation limits. No performance or memory measurement was made.
- No statement about stylus pressure, palm contact hardware behavior, PDF visual fidelity, font fallback, Android Canvas pixels, Zoom latency or camera anchoring is validated by this review. No device was available.

Verdict: fix findings 1–9, resolve the compile/lint failures, and rerun the affected integration tests before shipping. F14/F15 completion remains unproven even after these repairs.

## Parent repair disposition

The parent corrected findings 2/3/4 with strict export PDF admission, frozen opacity and image-only single-page guards; finding 5 with bounded group-depth validation and deep-chain tests; finding 7 with global custom-path cancellation and a two-pointer late-UP regression; finding 8 with per-tool Tape payload and regression. Finding 6 has bounded Android multi-finger recognition; hardware/router instrumentation remains unverified. Finding 9 now persists mode/target epoch and sends insertion to the captured session. Finding 1 now uses one noncancelable import/publication operation plus persistent publication receipts reconciled on next launch. Fault-injection and crash/power-loss tests for publication remain open. These repairs were not independently re-reviewed; combined verification is recorded separately.
