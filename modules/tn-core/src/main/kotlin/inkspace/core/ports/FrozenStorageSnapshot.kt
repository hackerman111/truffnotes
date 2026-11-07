package inkspace.core.ports

import inkspace.core.id.AssetId
import inkspace.core.model.SnapshotMetadata
import inkspace.core.model.SceneObject
import java.io.InputStream

/** Repeatable bounded record cursor; close releases the read transaction and asset pins. */
interface FrozenStorageSnapshot : AutoCloseable {
    val metadata: SnapshotMetadata
    val objectCount: Int
    val assets: Set<AssetId>
    fun records(): Sequence<SceneObject>
    fun asset(id: AssetId): InputStream
}
