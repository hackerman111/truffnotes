package inkspace.content

import inkspace.core.id.ObjectId

/** Per-view ephemeral state; has no write access to the document. */
class TapeRevealState {
    private val revealed = HashSet<ObjectId>()
    fun toggle(id: ObjectId) { if (!revealed.add(id)) revealed.remove(id) }
    fun isRevealed(id: ObjectId): Boolean = id in revealed
    fun clear() = revealed.clear()
}
