package inkspace.exchange

import inkspace.core.codec.CoreCodec
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.*
import inkspace.core.unit.DocRect
import java.io.InputStream
import java.io.OutputStream

sealed interface NativeScope {
    data object Full : NativeScope
    data class Pages(val ids: List<PageId>) : NativeScope
    /** Zero-based inclusive indices in document order. */
    data class PageRange(val first: Int, val last: Int) : NativeScope
    /** Whole editable objects intersecting the region; never a redaction or destructive geometry crop. */
    data class Region(val page: PageId, val bounds: DocRect) : NativeScope
    data class Objects(val ids: Set<ObjectId>) : NativeScope
}

data class SelectionReport(val dependencyObjects: Set<ObjectId>, val detachedLinks: Set<ObjectId>,
    val region: DocRect?, val selectedPages: List<PageId>, val selectedLayers: Set<LayerId>)
data class NativeProjection(val snapshot: DocumentSnapshot, val report: SelectionReport)

object NativeSelection {
    fun project(source: DocumentSnapshot, scope: NativeScope = NativeScope.Full,
        layerIds: Set<LayerId>? = null, visibleLayersOnly: Boolean = false): NativeProjection {
        require(source.directory.allResidentObjects().size == source.totalObjectCount) { "Selection requires a resident snapshot" }
        require(layerIds == null || layerIds.all { source.getLayer(it) != null }) { "Unknown selected layer" }
        val all = source.orderedSceneObjects().associateBy { it.id }
        val requestedPages: Set<PageId> = when (scope) {
            NativeScope.Full -> source.pages.map { it.id }.toSet()
            is NativeScope.Pages -> {
                require(scope.ids.isNotEmpty() && scope.ids.distinct().size == scope.ids.size)
                scope.ids.toSet()
            }
            is NativeScope.PageRange -> {
                require(scope.first >= 0 && scope.last >= scope.first && scope.last < source.pages.size)
                source.pages.subList(scope.first,scope.last + 1).map { it.id }.toSet()
            }
            is NativeScope.Region -> { require(scope.bounds.width > 0 && scope.bounds.height > 0); setOf(scope.page) }
            is NativeScope.Objects -> {
                require(scope.ids.isNotEmpty() && scope.ids.all { it in all }) { "Unknown selected object" }
                scope.ids.map { source.getLayer(all.getValue(it).layer)!!.pageId }.toSet()
            }
        }
        require(requestedPages.isNotEmpty() && requestedPages.all { source.getPage(it) != null }) { "Unknown selected page" }
        val pages = source.pages.filter { it.id in requestedPages }
        val layers = source.layers.filter { it.pageId in requestedPages && (layerIds == null || it.id in layerIds) && (!visibleLayersOnly || it.isVisible) }
        require(layers.isNotEmpty()) { "Selection contains no layers" }
        val allowedLayers = layers.map { it.id }.toSet()
        val eligible = all.filterValues { it.layer in allowedLayers }
        val seeds: Set<ObjectId> = when (scope) {
            is NativeScope.Objects -> { require(scope.ids.all { it in eligible }) { "Object excluded by selected layers" }; scope.ids }
            is NativeScope.Region -> eligible.values.filter { it.bounds.intersects(scope.bounds) }.map { it.id }.toSet()
            else -> eligible.keys
        }
        val selected = LinkedHashSet(seeds)
        val pending = java.util.ArrayDeque(seeds)
        fun include(id: ObjectId) {
            require(id in eligible) { "Object dependency missing or outside selected layers: ${id.value}" }
            if (selected.add(id)) pending.add(id)
        }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            source.parentGroupOf(id)?.let(::include)
            when (val payload = all.getValue(id).payload) {
                is GroupPayload -> payload.members.forEach(::include)
                is ConnectorPayload -> { include(payload.sourceId); include(payload.targetId) }
                is StickyPayload -> payload.attachedIds.forEach(::include)
                else -> Unit
            }
        }
        val detached = LinkedHashSet<ObjectId>()
        val objects = all.values.asSequence().filter { it.id in selected }.map { obj ->
            val link = obj.payload as? LinkAnchorPayload
            if (link?.targetPage != null && link.targetPage !in requestedPages) {
                detached += obj.id
                val payload = link.copy(targetPage = null)
                obj.copy(payload = payload, payloadDigest = CoreCodec.computePayloadDigest(payload))
            } else obj
        }
        val snapshot = restoreSnapshot(SnapshotMetadata(source.version,source.kind,pages,layers),objects)
        return NativeProjection(snapshot,SelectionReport((selected - seeds).toSet(),detached.toSet(),
            (scope as? NativeScope.Region)?.bounds,pages.map { it.id },allowedLayers))
    }
}

object NativeBundles {
    /** Template defaults explicitly to the first page; supply scope to choose another page. */
    fun write(source: DocumentSnapshot, purpose: NativePurpose, output: OutputStream,
        scope: NativeScope? = null, layerIds: Set<LayerId>? = null, visibleLayersOnly: Boolean = false,
        limits: ArchiveLimits = ArchiveLimits(), canceled: () -> Boolean = { false },
        openAsset: (AssetId) -> InputStream): NativeProjection {
        val effective = scope ?: if (purpose == NativePurpose.Template) NativeScope.PageRange(0,0) else NativeScope.Full
        require(purpose != NativePurpose.ObjectBundle || effective is NativeScope.Objects) { "Object bundle requires selected IDs" }
        val projection = NativeSelection.project(source,effective,layerIds,visibleLayersOnly)
        require(purpose != NativePurpose.Template || projection.snapshot.pages.size == 1) { "Choose one template page" }
        ResidentSnapshotReader(projection.snapshot,purpose,openAsset).use { NativeArchive.write(it,output,limits,canceled) }
        return projection
    }
}
