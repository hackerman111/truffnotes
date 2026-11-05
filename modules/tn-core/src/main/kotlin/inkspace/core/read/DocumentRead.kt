package inkspace.core.read

import inkspace.core.id.ObjectId
import inkspace.core.model.DocVersion
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
}
