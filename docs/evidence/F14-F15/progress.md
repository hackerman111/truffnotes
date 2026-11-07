# Editor repair and F14–F15 implementation ledger

Plan: docs/superpowers/plans/2026-11-05-truffnotes-android.md. Spec: truffnotes.md. Branch: fix/editor-through-f15.

- Previous partial editor repair is retained; active-session/input/PDF import and shared replay regressions are fixed.
- Completed PDF backend task: source form composition, supported standard annotations, flattened appearances, navigation and six structural/two-viewer fixtures. Evidence: pdf-completion.md.
- Completed recovery task: streamed frozen generation/asset leases, cold records, pending ENOSPC edits and watermark regression; safe fresh SAF output with read-back receipt.
- Completed native/UI task: scoped exports with preview, template page selection/registration, multi-document collection hierarchy/name preservation and atomic catalog publication/retry/rollback.
- Completed F15 task: palm posture, timer history persistence, PDF search highlight/navigation, resize center/cancel fixes, settings and host fixtures. Evidence: f15-completion.md.
- Review findings corrected: template region mapping, cold export completeness and admission, capture/pin race, timed-out lease cleanup, cold preview, all-layer streaming route and stale preview.
- Final Gradle check/build PASS. 258 tests, no failures/errors/skips. Independent qpdf/Poppler/MuPDF validation PASS for six backend PDF fixtures.
- Physical Android acceptance remains unexecuted. Do not mark phase gates passed using host evidence.
- Ruling: continue the existing Android plan in the current task branch; no redesign, merge or publication was requested.
- Ruling: F20 notifications/presentation remain outside F15; initial laser/timer/history are implemented now.
- Ruling: explicit PDF fallback/admission reports are preserved; unsupported PDF actions are not silently claimed equivalent.
