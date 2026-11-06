package inkspace.core.content

import inkspace.core.id.AssetId
import inkspace.core.id.ObjectId
import inkspace.core.ports.StagedAssetRef

interface AssetStagingPort {
    fun stage(source: java.io.InputStream, maxBytes: Long, suffix: String): StagedAssetRef
    fun open(asset: AssetId): java.io.InputStream
}

data class ContentImportResult(val asset: StagedAssetRef, val width: Int, val height: Int, val exifOrientation: Int)

data class ContentFailure(val reason: String, val recoverable: Boolean = true)

data class ContentViewState(val revealedTapeIds: Set<ObjectId> = emptySet(), val collapsedStickyIds: Set<ObjectId> = emptySet())
