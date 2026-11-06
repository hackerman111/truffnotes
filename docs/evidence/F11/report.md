# F11 implementation evidence

Phase / Requirement IDs: F11 / R18, R20, R38
Revision: working tree, 2026-11-06; no commit created.
Status: partial implementation; phase gate is open.

Changed paths: modules/tn-content (JVM module, planners/budget models, tests).
Core schema/API changes: none. Uses existing core Image/Sticky/Tape payloads and core reducer.

Implemented:
- Image header dimension validation at 100MP and power-of-two decode planning capped at 32MiB, including EXIF rotation/mirroring descriptor.
- Transactional image insert/rectangular crop preserving source asset, Sticky insert/collapse and Tape insert/remove; entire remove batch resolves before any edits.
- Per-view Tape reveal has no access to the document or a write transaction, so read-only reveal is possible.
- Editable ObjectBundle frozen snapshots, internal group/connector ID remapping and asset reference enumeration.
- GIF frame/decoded-byte admission budget and visibility-based pause decision.

Verification:
- Tests first; initial Gradle blocked by read-only wrapper lock.
- Concurrent elevated attempts interrupted (exit 130) after Kotlin incremental-cache collisions, not a successful test run.
- Eight tests cover decode bomb/dimensions, EXIF descriptor, GIF budget, ephemeral reveal, bundle remapping, snapshot alias isolation, crop reducer undo and atomic failed Tape removal.
- `/tmp/f09-f13-build4.log`: seven tests passed; newly added shape-vertex alias regression failed as expected. ObjectBundle deep copy fixed after observing that failure; final rerun is recorded by the parent.
Device: none. No camera, picker, permission, codec or GIF decoder instrumentation executed.

Remaining requirements:
- Android gallery/SAF/camera/clipboard/drag/drop adapters and staged asset integration; actual bounded decoder and tile pipeline, freehand crop mask, Elements collection CRUD/persistence/insertion, screenshot port routing.
- Sticky rich text/attached children and Tape path/pattern persistence need additive core schema; current payloads only support text/color/collapse and rectangle/color/opacity respectively.
- GIF codec, source staging and poster-frame render/export are not implemented; the budget class alone is not a decoder.
- Runtime submitContentAction is wired through the existing engine and SubmitEdit; UI remains open. W4 memory gate and durable reload integration are open.

Final verification: root ktlintCheck/detekt/jvmTestAll/architectureCheck/apiCheck/requirementsCheck/androidUnitTestAll/lint/assembleDebug completed with exit 0. Raw log, test counts and source hashes: [combined report](../F09-13/report.md). Full phase gate remains OPEN.
Runtime test verifies Sticky content survives durable close/reopen alongside text and pages.
