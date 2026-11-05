package inkspace.testsupport

import inkspace.core.id.DocumentId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.model.DocVersion
import inkspace.core.payload.ObjectPayload
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocRect

class FakeDocumentRead(
    override val version: DocVersion = DocVersion(DocumentId("doc-test-001"), Revision(0L)),
    private val residentObjects: Map<ObjectId, ObjectPayload> = emptyMap(),
    private val nonResidentIds: Set<ObjectId> = emptySet(),
    private val lockedIds: Set<ObjectId> = emptySet(),
    private val boundsMap: Map<ObjectId, DocRect> = emptyMap()
) : DocumentRead {

    override val totalObjectCount: Int
        get() = residentObjects.size + nonResidentIds.size

    override fun objectState(id: ObjectId): ResidencyState {
        return when {
            residentObjects.containsKey(id) -> ResidencyState.Present
            nonResidentIds.contains(id) -> ResidencyState.NotResident
            else -> ResidencyState.Missing
        }
    }

    override fun isLocked(id: ObjectId): Boolean = lockedIds.contains(id)

    override fun getObjectPayload(id: ObjectId): ObjectPayload? = residentObjects[id]

    override fun queryBounds(rect: DocRect): List<ObjectId> {
        return boundsMap.filter { (_, b) -> b.intersects(rect) }.keys.toList()
    }
}
