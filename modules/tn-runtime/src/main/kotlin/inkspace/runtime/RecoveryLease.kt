package inkspace.runtime

import inkspace.core.id.AssetId
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.SceneObject
import inkspace.core.payload.referencedAssetIds
import inkspace.exchange.*
import inkspace.store.DocumentStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Holds one applied generation and all source assets; it never writes to the failed storage volume. */
class RecoveryLease internal constructor(val snapshot: DocumentSnapshot, store: DocumentStore?, root: File,
    private val memoryPin: AutoCloseable) : SnapshotReader {
    private val closed = AtomicBoolean()
    private val resident = snapshot.directory.allResidentObjects().sortedBy { it.id.value }
    private val base = if(resident.size == snapshot.totalObjectCount) null else requireNotNull(store).acquireSnapshot(snapshot.version.document)
    init { require(base == null || base.metadata.version.revision.value <= snapshot.version.revision.value) { "Storage generation is newer than export snapshot" } }
    private val candidates = snapshot.referencedAssets() + base?.assets.orEmpty()
    private val pins = try { store?.pinAssets(snapshot.version.document,candidates) } catch(error: Exception) { base?.close(); memoryPin.close(); throw error }
    private val assetRoot = File(root,"documents/${snapshot.version.document.value}/assets")
    private val reachable = try {
        val ids = linkedSetOf<AssetId>()
        snapshot.pages.forEach { it.metadata.template?.asset?.let(ids::add); it.metadata.pdfBackground?.asset?.let(ids::add) }
        records().forEach { ids.addAll(it.payload.referencedAssetIds()) }
        ids
    } catch(error: Exception) { close(); throw error }
    override val document = NativeDocument(snapshot.version,snapshot.kind,snapshot.pages,snapshot.layers,snapshot.totalObjectCount,reachable.map { it.value }.sorted())
    override fun records(): Sequence<SceneObject> = sequence {
        check(!closed.get())
        val disk = base?.records()?.filter { snapshot.directory.objectState(it.id) == inkspace.core.read.ResidencyState.NotResident }?.onEach {
            check(snapshot.directory.objectVersion(it.id)==it.version) { "Cold record generation mismatch" }
        }?.iterator()
        val memory = resident.iterator()
        var a = disk?.let { if(it.hasNext()) it.next() else null }
        var b = if(memory.hasNext()) memory.next() else null
        while(a != null || b != null) {
            check(!closed.get())
            if(b == null || (a != null && a.id.value < b.id.value)) { yield(a!!); a = if(disk!!.hasNext()) disk.next() else null }
            else { yield(b); b = if(memory.hasNext()) memory.next() else null }
        }
    }
    fun residentSnapshot(pageIds: Set<inkspace.core.id.PageId>? = null, admitColdBytes: (Long) -> Unit = {}): DocumentSnapshot {
        if(resident.size==snapshot.totalObjectCount)return snapshot
        var coldBytes=0L
        val selectedLayers=pageIds?.let { pages -> snapshot.layers.filter {it.pageId in pages}.map {it.id}.toSet() }
        val admitted=records().filter {selectedLayers == null || it.layer in selectedLayers}.onEach { if(snapshot.directory.objectState(it.id)==inkspace.core.read.ResidencyState.NotResident) {
            coldBytes+=it.estimatedMemoryBytes();admitColdBytes(coldBytes)
        } }
        val restored=inkspace.core.model.restoreSnapshot(inkspace.core.model.SnapshotMetadata(snapshot.version,snapshot.kind,snapshot.pages,snapshot.layers),admitted)
        check(restored.totalObjectCount==(selectedLayers?.let {snapshot.directory.queryLayers(it).size} ?: snapshot.totalObjectCount)) { "Missing cold export records" }
        return restored
    }
    override fun asset(id: AssetId): java.io.InputStream {
        check(!closed.get()); require(id in reachable)
        val hash = id.value.removePrefix("sha256:")
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(assetRoot,"${hash.take(2)}/$hash").inputStream()
    }
    override fun close() { if(closed.compareAndSet(false,true)) { try { pins?.close() } finally { try { base?.close() } finally { memoryPin.close() } } } }
}
