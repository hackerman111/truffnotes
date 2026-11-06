# F10 implementation evidence

Phase / Requirement IDs: F10 / R19, R39
Revision: working tree, 2026-11-06; no commit created.
Status: partial implementation; phase gate is open.

Changed paths: modules/tn-text (Android library, pure editing model, tests).
Core schema/API changes: none. TextPlanner consumes existing TextPayload, TextRun, PlanContext and returns core EditPlan; no sibling implementation imports.

Implemented:
- UTF-16 grapheme boundaries and explicit UTF-8 offset mapping, including emoji/combining/ZWJ cases.
- Focus epoch, local composition replacement/cancel, selection and surrounding deletion without splitting graphemes, existing style-run preservation.
- Create/replace plans with object-version preconditions, locked-layer checks and single core mutation route.
- Android StaticLayout with system font fallback, bounded LRU, the same glyph positions for paint and hit test; bold/italic/underline/color spans supported by existing core payload.
- Native IME InputConnection and editable accessibility node; batch/composition commit invokes one model callback.

Verification:
- Initial sandbox Gradle invocation failed before configuration with read-only ~/.gradle wrapper lock.
- Elevated concurrent Gradle attempts were interrupted (exit 130) after shared Kotlin incremental-cache collisions. These are NOT passing source or Android checks.
- Combined integration run `/tmp/f09-f13-build4.log`: `:tn-text:testDebugUnitTest` PASS, all eight pure model/planner tests. Production Android Kotlin compilation also passed. Other combined tasks failed separately, so this is not a green whole-project run.
Device: none. Android IME instrumentation on two IMEs, glyph goldens and performance/heap measurements have not run.

Remaining requirements:
- Full rich styling (strike, highlight, per-run font/size, lists, indent, alignment and paragraph spacing) needs additive core contracts; imported-font staging/digest limits are not implemented.
- Runtime/UI must bind the native editor and consume its callback through the actor. Renderer/export must consume TextPaintPlan instead of their current text replay; current core TextPort returns metrics only, so exact rich glyph replay cannot be claimed across modules.
- Full-page typing, pin setting, transformed selection overlay and layout-cache registration in the shared memory governor remain integration work.
- InputConnection still needs composing-region requests, codepoint deletion, arbitrary IME relative-cursor positions, updateSelection/restartInput and focus-loss instrumentation before declaring a complete Android IME host.

Final verification: root ktlintCheck/detekt/jvmTestAll/architectureCheck/apiCheck/requirementsCheck/androidUnitTestAll/lint/assembleDebug completed with exit 0. Raw log, test counts and source hashes: [combined report](../F09-13/report.md). Full phase gate remains OPEN.
Runtime submitTextAction now routes the planner through the existing engine and SubmitEdit. Native IME UI binding and rich renderer integration remain open.
