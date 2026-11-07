package inkspace.core.model

import inkspace.core.codec.CoreCodec
import inkspace.core.id.ObjectId
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.LinkAnchorPayload
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotMetadata(val version: DocVersion, val kind: DocumentKind,
    val pages: List<PageDescriptor>, val layers: List<LayerDescriptor>)

/** Trust boundary used by native import and store checkpoints, never a mutation bypass for live state. */
fun restoreSnapshot(metadata: SnapshotMetadata, records: Sequence<SceneObject>): DocumentSnapshot {
    require(metadata.pages.isNotEmpty() && metadata.layers.isNotEmpty())
    require(metadata.pages.map { it.id }.distinct().size == metadata.pages.size)
    require(metadata.layers.map { it.id }.distinct().size == metadata.layers.size)
    val pages = metadata.pages.associateBy { it.id }
    val layers = metadata.layers.associateBy { it.id }
    require(metadata.layers.all { it.pageId in pages })
    var directory = ChunkedObjectDirectory()
    val parents = LinkedHashMap<ObjectId, ObjectId>()
    for (record in records) {
        require(directory.getObject(record.id) == null) { "Duplicate object ID" }
        val layer = requireNotNull(layers[record.layer]) { "Missing object layer" }
        require(pages.getValue(layer.pageId).spaceId == record.space) { "Object space mismatch" }
        require(record.payloadDigest == CoreCodec.computePayloadDigest(record.payload)) { "Payload digest mismatch" }
        directory = directory.putEntry(DirectoryEntry.Present(record.frozen()))
        val group = record.payload as? GroupPayload
        group?.members?.forEach { member -> require(member != record.id && parents.put(member, record.id) == null) { "Repeated group parent" } }
        val link = record.payload as? LinkAnchorPayload
        require(link?.targetPage == null || link.targetPage in pages) { "Missing link target" }
    }
    for ((member, parent) in parents) {
        val objectValue = requireNotNull(directory.getObject(member)) { "Missing group member" }
        val group = requireNotNull(directory.getObject(parent))
        require(objectValue.space == group.space && objectValue.layer == group.layer)
        var ancestor: ObjectId? = parent
        val seen = HashSet<ObjectId>()
        while (ancestor != null) { require(seen.add(ancestor)) { "Group cycle" }; require(seen.size <= inkspace.core.limits.Limits().maxGroupDepth) { "Group depth exceeds limit" }; ancestor = parents[ancestor] }
    }
    for (record in directory.allResidentObjects()) {
        when (val payload = record.payload) {
            is inkspace.core.payload.ConnectorPayload -> {
                require(directory.getObject(payload.sourceId) != null && directory.getObject(payload.targetId) != null) { "Missing connector endpoint" }
            }
            is inkspace.core.payload.StickyPayload -> require(payload.attachedIds.all { directory.getObject(it) != null }) { "Missing sticky attachment" }
            else -> Unit
        }
    }
    val snapshot = DocumentSnapshot(metadata.version, metadata.kind, directory, metadata.pages, metadata.layers, pages, layers, parents)
    require(snapshot.verifyInvariants())
    return snapshot
}
