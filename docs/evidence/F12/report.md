# F12 implementation and validation record

Requirements: R02/R03/R04/R05/R24/R36. Source paths below are the implementation fingerprint; parent integration records the final revision and full checks.

## Implemented paths

- `tn-library/Library.kt`: atomic catalog updates with operation IDs, create/folder/rename/move/favorite/trash/restore, inherited folder Trash, cycle/path validation, defaults/templates queries and updates, load/write byte cap 8MiB, maximum 10,000 entries. Templates clone through the runtime document copier; they do not alias the source document.
- `tn-library/ThumbnailJobs.kt`: two workers, queue 16, 3-second deadline, LRU cache cap 4MiB; native ARGB thumbnail values are bounded to 256×256. A changed document invalidates its thumbnails.
- `tn-workspace/Workspace.kt`: open/switch/close/reopen/reorder, up to 1,000 metadata-only tabs and 100 persisted closed tabs. Camera, selected IDs, active page/layer, tool, ReadOnly, Focus and settings live in metadata. Dirty close requests a flush and UI retries while retaining pending edits.
- `tn-core/workspace/WorkspaceContracts.kt`: shared command/action/state DTOs; UI imports core contracts only.
- `tn-runtime/WorkspaceGateway.kt`: durable metadata, event-bound session transitions, rejection rollback, feature planner callbacks, read-only validation, actual tool dispatch, text pin behavior, off-Main import worker (one worker, four queued jobs), visible recoverable import failures.
- `tn-ui/NotebookShell.kt`: virtualized grid/list; Documents/Favorites/Recent/Templates/Trash; name search; long-press menu; multi-select and bulk actions; folder navigation; configurable notebook creation; actual image/PDF pickers; template copies; tab overflow/reorder/reopen; toolbar and keys through shared commands; Pen repeat/long-press settings; object menu; Pages/Outline/Bookmarks/Layers controls; native text editor factory; adaptive dock/overlay, safe insets, Back, ReadOnly and Focus.
- `tn-ui/BaselineEditorScreen.kt`: forwards the actual Android Surface on create/change. Existing save/error/input handling remains in the baseline editor.
- `app/MainActivity.kt`: SAF launchers, captured-session keyed editor, native text and actual Surface factories. Existing saved viewport bundle handles Activity recreation.
- Runtime feature/renderer/Android adapters, wired by the parent integrator, apply page/layer/outline/bookmark/object/text/content commands to the existing document reducer and bind the displayed Surface. The shell does not create a second document model.

## Original acceptance coverage

| Cases | Implemented UI route | Automated check |
|---|---|---|
| 1–4,47 | Documents → + New → Notebook, cover/paper/size/orientation/scroll defaults, editable Pen page | `acceptance01to04and47_firstDocumentsCreatesConfiguredNotebook` |
| 5–6 | Long press → Rename/Move/Favorite/Trash/Restore; folder destinations; bulk selection | `acceptance05and06_longPressMovesNotebookToFolder`, LibraryTest |
| 7–10 | Metadata tabs, selected marker, switch/close/reopen/order/overflow; dirty-save guard | `acceptance07to10and15_tabsSwitchWithoutClosingAndUndoIsDirect`, WorkspaceTest, F12WorkspaceGatewayTest |
| 11–16 | Shared toolbar commands, repeat Pen settings, Eraser, Undo/Redo, Pages | `acceptance11to14and16_toolbarSelectsToolsRepeatsSettingsAndShowsPages` |
| 17–18 | ReadOnly validated in gateway/core; Focus hides toolbar/sidebars without changing camera | `acceptance17and18_readOnlyAndFocusUseSharedCommands`, WorkspaceTest, F12WorkspaceGatewayTest |

The Compose tests use a UI gateway fixture to inspect user interactions. Runtime integration tests exercise the production library/workspace and feature dispatcher. Neither is a substitute for a device end-to-end test.

## Tests and commands

- LibraryTest covers atomic reload/bulk validation, folder cycles, inherited Trash/restoration, encoded size cap, invalid IDs on creation/reload, and persisted template/default updates.
- ThumbnailJobsTest covers cache reuse, byte cap, invalidation and dimension rejection.
- WorkspaceTest covers 100 cold tabs, dirty close, commands, Focus camera, text focus/key isolation, ReadOnly, 1,000-tab limit and reconstructed closed-tab/view metadata.
- F12WorkspaceGatewayTest covers production catalog/view reconstruction, failed metadata writes, real feature callback routing, custom page dimensions, matching SessionOpened transition barrier, restored active tab opening, queue rejection rollback, text-pin transitions, read-only mutation rejection and off-Main library import.
- F04AppLifecycleAndCallbackProfileTest includes stale Surface destruction regression, preserving the new session's Surface.
- F12NotebookUiInstrumentedTest contains the original acceptance interaction cases above.

Initial offline Gradle launch failed before tests due sandbox read-only Gradle wrapper lock. An early concurrent build was interrupted after Kotlin incremental cache contention. Parent serialized subsequent Gradle runs with `-Pkotlin.incremental=false --max-workers=2`; final commands, exit codes and XML test counts belong in the parent integration report.

Observed compilation: `:tn-ui:compileDebugKotlin` passed in `/tmp/f09-13-integration-red.log`. The initial UI Android instrumentation compile could not resolve uncached `ui-test-junit4:1.10.6` offline in `/tmp/f09-13-full-integration1.log`; dependency resolution and final rerun are owned by the parent. No device execution is claimed here.

## Remaining validation gates

No real tablet/emulator UI run has been performed by the F12 implementer. Portrait/landscape/split-size behavior, actual IME focus and transformed cursor geometry, Surface display, the create→write→undo→close→kill→reopen path, input callback timing, thumbnail latency and native/graphics memory need Android execution. No traces, hardware percentiles or UI acceptance PASS results are fabricated. Final phase validation remains open until those gates run.

## Observed integration rerun

`/tmp/f09-13-full-integration2.log`: app compilation and UI Android instrumentation compilation completed without compilation errors. F12 production tests observed from XML: LibraryTest 6 passed, ThumbnailJobsTest 1 passed, WorkspaceTest 4 passed, F12WorkspaceGatewayTest 8 passed (19 total, zero failures). This combined command exited nonzero because `F04RuntimeQueuesAndMemoryTest.shutdown_pending(File)` failed outside these F12 tests; parent is resolving and rerunning the full suite. UI instrumentation was compiled, not executed on a device.
