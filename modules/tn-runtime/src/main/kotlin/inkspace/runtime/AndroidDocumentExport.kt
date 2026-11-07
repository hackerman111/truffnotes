package inkspace.runtime

import android.content.Context
import android.net.Uri
import inkspace.core.id.ObjectId
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.SessionKey
import inkspace.core.runtime.MemoryCategory
import inkspace.exchange.*
import java.io.File

data class DocumentExportResult(val revision: Long, val report: List<String>)

object AndroidDocumentExport {
    fun export(context: Context, runtime: Runtime, session: SessionKey, snapshot: DocumentSnapshot,
        uri: Uri, mode: String, scope: String, selectedIds: Set<String>,
        options: inkspace.core.workspace.ExportOptions = inkspace.core.workspace.ExportOptions(mode=mode,scope=scope)): DocumentExportResult {
        runtime.acquireRecovery(session).use { lease ->
            val fullNative=mode=="Native" && options.scope=="full" && options.layerIds==null && !options.visibleOnly
            val coldPins=mutableListOf<AutoCloseable>()
            var reservedCold=0L
            try {
            val original=lease.snapshot
            val active=original.pages.getOrNull(options.firstPage-1)?.id ?: error("Export page is outside the captured document")
            val selectedPages=when(options.scope) {
                "page","region" -> setOf(active)
                "range" -> original.pages.subList(options.firstPage-1,options.lastPage).map {it.id}.toSet()
                "template" -> setOf(original.pages[options.firstPage-1].id)
                "selection","objects" -> original.pages.filter { page ->
                    val layers=original.layers.filter {it.pageId==page.id}.map {it.id}.toSet()
                    original.directory.queryLayers(layers).any {it.value in selectedIds}
                }.map {it.id}.toSet()
                else -> null
            }
            val frozen = if(fullNative) original else lease.residentSnapshot(selectedPages) { needed ->
                if(needed>reservedCold) {
                    val additional=((needed-reservedCold+65535)/65536)*65536
                    coldPins+=runtime.governor.tryReserve(MemoryCategory.RESIDENT_MODEL_PAYLOAD,additional,"export-cold-payloads").getOrNull()
                        ?: error("Selected export exceeds resident payload budget; export the full native document")
                    reservedCold+=additional
                }
            }
            var warnings=emptyList<String>()
            if (mode == "Native") {
                if (options.scope == "full" && options.layerIds == null && !options.visibleOnly) {
                    SafeExport.write(AndroidExchangeOutput(context.contentResolver,uri)) { NativeArchive.write(lease,it) }
                } else AndroidNativeExchange(context, File(runtime.config.storageRootPath), runtime::snapshotForThumbnail).exportNative(frozen, uri,
                    scope = when (options.scope) {
                        "page" -> NativeScope.Pages(listOf(frozen.pages[options.firstPage-1].id))
                        "selection", "objects" -> NativeScope.Objects(selectedIds.map(::ObjectId).toSet())
                        "range" -> NativeScope.PageRange(options.firstPage-1,options.lastPage-1)
                        "region" -> NativeScope.Region(frozen.pages[options.firstPage-1].id,requireNotNull(options.region))
                        "template" -> NativeScope.PageRange(options.firstPage-1,options.firstPage-1)
                        else -> null
                    }, purpose = when (options.scope) { "template" -> NativePurpose.Template; "objects" -> NativePurpose.ObjectBundle; else -> NativePurpose.Document },
                    visibleLayersOnly = options.visibleOnly || options.scope == "visible", layerIds = options.layerIds?.map { inkspace.core.id.LayerId(it) }?.toSet())
            } else {
                val report = runtime.exportPages(session,frozen,uri,ExportMode.valueOf(mode),options.copy(objectIds=selectedIds))
                val receipt = File(context.filesDir,"last-export-report.txt")
                warnings=report
                runCatching {receipt.writeText("Revision ${frozen.version.revision.value}\n"+report.joinToString("\n"))}
            }
            return DocumentExportResult(frozen.version.revision.value,warnings)
            } finally { coldPins.asReversed().forEach {it.close()} }
        }
    }
    fun timer(initial: inkspace.core.workspace.TimerSnapshot): inkspace.core.workspace.AccessoryTimer = inkspace.media.TimerAccessory(initial, android.os.SystemClock::elapsedRealtimeNanos)
}
