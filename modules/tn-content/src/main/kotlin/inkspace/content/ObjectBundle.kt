package inkspace.content

import inkspace.core.id.AssetId
import inkspace.core.id.ObjectId
import inkspace.core.model.SceneObject
import inkspace.core.payload.*

/** Editable collection snapshot; all references to included objects are remapped on insertion. */
class ObjectBundle(schema: Int, objects: List<SceneObject>, rootIds: Set<ObjectId>) {
    val schema: Int = schema
    val objects: List<SceneObject> = java.util.List.copyOf(objects.map { obj ->
        val payload = when (val payload = obj.payload) {
            is ShapePayload -> payload.copy(vertices = java.util.List.copyOf(payload.vertices), controlPoints = java.util.List.copyOf(payload.controlPoints), label = payload.label?.let { it.copy(runs = java.util.List.copyOf(it.runs)) })
            is ConnectorPayload -> payload.copy(controlPoints = java.util.List.copyOf(payload.controlPoints))
            is ImagePayload -> payload.copy(cropMask = java.util.List.copyOf(payload.cropMask))
            is TapePayload -> payload.copy(points = java.util.List.copyOf(payload.points))
            is StickyPayload -> payload.copy(attachedIds = java.util.List.copyOf(payload.attachedIds), richText = payload.richText?.let { it.copy(runs = java.util.List.copyOf(it.runs)) })
            else -> payload
        }
        obj.copy(payload = payload).frozen()
    })
    val rootIds: Set<ObjectId> = java.util.Set.copyOf(rootIds)
    val assetRefs: Set<AssetId> = java.util.Set.copyOf(buildSet {
        for (obj in objects) {
            (obj.payload as? ImagePayload)?.let { add(it.asset) }
            val text = when (val payload = obj.payload) { is TextPayload -> payload; is ShapePayload -> payload.label; is StickyPayload -> payload.richText; else -> null }
            text?.fontAsset?.let { add(it) }
            text?.runs?.forEach { it.fontAsset?.let { font -> add(font) } }
        }
    })
    init {
        require(schema == 1) { "Unsupported editable bundle schema" }
        require(objects.isNotEmpty() && objects.size <= 10_000)
        val ids = objects.map { it.id }.toSet()
        require(ids.size == objects.size && rootIds.isNotEmpty() && ids.containsAll(rootIds))
        for (obj in objects) {
            val payload = obj.payload
            if (payload is GroupPayload) require(ids.containsAll(payload.members)) { "Missing group member" }
            if (payload is StickyPayload) require(ids.containsAll(payload.attachedIds)) { "Missing attached child" }
        }
        inInsertionOrder()
    }
    fun inInsertionOrder(): List<SceneObject> {
        val byId = objects.associateBy { it.id }
        val result = ArrayList<SceneObject>()
        val finished = HashSet<ObjectId>()
        val visiting = HashSet<ObjectId>()
        fun visit(id: ObjectId, depth: Int) {
            require(depth <= 32 && visiting.add(id)) { "Bundle cycle or depth limit" }
            if (id !in finished) {
                val obj = byId.getValue(id)
                val references = when (val payload = obj.payload) {
                    is GroupPayload -> payload.members
                    is StickyPayload -> payload.attachedIds
                    is ConnectorPayload -> listOf(payload.sourceId, payload.targetId).filter { it in byId }
                    else -> emptyList()
                }
                references.forEach { if (it !in finished) visit(it, depth + 1) }
                finished.add(id); result.add(obj)
            }
            visiting.remove(id)
        }
        objects.forEach { if (it.id !in finished) visit(it.id, 1) }
        return java.util.List.copyOf(result)
    }
    fun requestImage(render: inkspace.core.ports.RenderPort, scale: Float = 1f): inkspace.core.outcome.Outcome<inkspace.core.ports.RequestId, inkspace.core.error.SubmitError> {
        require(scale.isFinite() && scale > 0)
        val region = objects.map { it.bounds }.reduce { first, next -> first.union(next) }
        return render.trySubmit(inkspace.core.ports.RenderJob.Snapshot(region, scale))
    }
    fun remap(prefix: String): ObjectBundle {
        require(prefix.isNotBlank() && prefix.length <= 128)
        val ids = objects.associate { it.id to ObjectId("$prefix-${it.id.value}") }
        val copies = objects.map { obj ->
            val payload = when (val payload = obj.payload) {
                is GroupPayload -> payload.copy(members = payload.members.map { ids.getValue(it) })
                is StickyPayload -> payload.copy(attachedIds = payload.attachedIds.map { ids.getValue(it) })
                is ConnectorPayload -> payload.copy(sourceId = ids[payload.sourceId] ?: payload.sourceId,
                    targetId = ids[payload.targetId] ?: payload.targetId)
                else -> payload
            }
            obj.copy(id = ids.getValue(obj.id), payload = payload, version = 1L,
                payloadDigest = inkspace.core.codec.CoreCodec.computePayloadDigest(payload)).frozen()
        }
        return ObjectBundle(schema, copies, rootIds.map { ids.getValue(it) }.toSet())
    }
}
