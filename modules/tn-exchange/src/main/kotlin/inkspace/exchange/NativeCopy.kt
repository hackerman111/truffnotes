package inkspace.exchange

import inkspace.core.codec.CoreCodec
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.*

/** Explicit copy preserves assets and revision, remapping every document-local reference. */
fun nativeCopy(snapshot: DocumentSnapshot, newId: DocumentId): DocumentSnapshot {
    require(newId != snapshot.version.document)
    val prefix = newId.value + "-"
    val spaces = snapshot.pages.map { it.spaceId }.distinct().mapIndexed { index, id -> id to SpaceId("${prefix}s$index") }.toMap()
    val pages = snapshot.pages.mapIndexed { index, page -> page.id to PageId("${prefix}p$index") }.toMap()
    val layers = snapshot.layers.mapIndexed { index, layer -> layer.id to LayerId("${prefix}l$index") }.toMap()
    val objects = snapshot.orderedSceneObjects().mapIndexed { index, obj -> obj.id to ObjectId("${prefix}o$index") }.toMap()
    val result = snapshot.orderedSceneObjects().asSequence().map { obj ->
        val payload = when (val value = obj.payload) {
            is GroupPayload -> value.copy(members = value.members.map(objects::getValue))
            is ConnectorPayload -> value.copy(sourceId = objects.getValue(value.sourceId), targetId = objects.getValue(value.targetId))
            is StickyPayload -> value.copy(attachedIds = value.attachedIds.map(objects::getValue))
            is LinkAnchorPayload -> value.copy(targetPage = value.targetPage?.let(pages::getValue))
            else -> value
        }
        obj.copy(id = objects.getValue(obj.id), space = spaces.getValue(obj.space), layer = layers.getValue(obj.layer),
            payload = payload, payloadDigest = CoreCodec.computePayloadDigest(payload))
    }
    return restoreSnapshot(SnapshotMetadata(snapshot.version.copy(document = newId), snapshot.kind,
        snapshot.pages.map { it.copy(id = pages.getValue(it.id), spaceId = spaces.getValue(it.spaceId)) },
        snapshot.layers.map { it.copy(id = layers.getValue(it.id), pageId = pages.getValue(it.pageId), groupId = it.groupId?.let { group -> prefix + group }) }), result)
}
