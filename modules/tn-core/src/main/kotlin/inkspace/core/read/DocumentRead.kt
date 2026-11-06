package inkspace.core.read

import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.model.DocVersion
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.payload.ObjectPayload
import inkspace.core.unit.DocRect

enum class ResidencyState {
    Present,
    NotResident,
    Missing
}

interface DocumentRead {
    val version: DocVersion
    val totalObjectCount: Int
    fun objectState(id: ObjectId): ResidencyState
    fun isLocked(id: ObjectId): Boolean
    fun getObjectPayload(id: ObjectId): ObjectPayload?
    fun queryBounds(rect: DocRect): List<ObjectId>

    fun objectIdsInLayers(layers: Set<LayerId>): List<ObjectId> = queryBounds(DocRect(-1e9,-1e9,1e9,1e9)).filter {
        val obj = getObject(it) ?: throw IllegalArgumentException("Object directory lacks layer header")
        obj.layer in layers
    }
    fun getObject(id: ObjectId): SceneObject? = null
    fun getObjectVersion(id: ObjectId): Long? = getObject(id)?.version
    fun isLayerLocked(layerId: LayerId): Boolean = getLayer(layerId)?.isLocked ?: false
    fun getPage(pageId: PageId): PageDescriptor? = null
    fun getLayer(layerId: LayerId): LayerDescriptor? = null
    fun parentGroupOf(id: ObjectId): ObjectId? = null
    val pages: List<PageDescriptor> get() = emptyList()
    val layers: List<LayerDescriptor> get() = emptyList()
}
