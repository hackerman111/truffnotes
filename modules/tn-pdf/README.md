# tn-pdf

Android PDF capability depending only on tn-core. Native PDFium is loaded exclusively inside non-exported PdfIsolatedService. AndroidPdfPort supplies bounded core PdfPort requests through Messenger metadata and SharedMemory raster buffers.

The app owns one port for all documents. Provide openAsset as a read-only seekable descriptor resolver; receive typed replies through callback or poll. close() unbinds; cancel() discards callbacks and closes a canceled open after native completion. Service death invalidates handles; reopen immutable assets in the new epoch.

PdfImportPlanner creates ordinary reversible core plans for ranges, backgrounds and on-canvas resources. PDF sources remain immutable; annotations remain ordinary separate objects. Text paging consumes PdfReply.Text.nextIndex while spans contain UTF16 offsets for selection.

AndroidPdfWorkspace in runtime supplies staging, imports, viewport tiles and text/search/links/outline using the existing worker and memory governor. All native waits belong off Main.

See docs/evidence/F13/report.md for test evidence and outstanding device/performance gates.
