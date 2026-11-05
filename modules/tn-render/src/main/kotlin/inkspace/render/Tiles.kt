package inkspace.render

import inkspace.core.error.RenderError
import inkspace.core.outcome.Outcome
import inkspace.core.render.TileArtifact
import inkspace.core.runtime.MemoryCategory

data class TileCacheKey(
    val tileKey: String,
    val scaleBucket: Int
)

/**
 * Bounded LRU cache for raster/PDF/image tiles (`CPU_RASTER_TILES`, default 32 MiB).
 */
class RasterTileCache(
    val maxBytes: Long = MemoryCategory.CPU_RASTER_TILES.defaultCapBytes
) {
    private val entries = LinkedHashMap<TileCacheKey, TileArtifact>(32, 0.75f, true)
    private var currentBytes: Long = 0L

    @Synchronized
    fun currentUsedBytes(): Long = currentBytes

    @Synchronized
    fun getOrAdmit(
        tile: TileArtifact,
        onHit: () -> Unit
    ): Outcome<TileArtifact, RenderError> {
        val key = TileCacheKey(tile.tileKey, tile.scaleBucket)
        val existing = entries[key]
        if (existing != null) {
            onHit()
            return Outcome.Success(existing)
        }

        val tileBytes = tile.byteSize
        if (tileBytes > maxBytes) {
            return Outcome.Failure(
                RenderError.MemoryBudgetExceeded(
                    category = MemoryCategory.CPU_RASTER_TILES.name,
                    requestedBytes = tileBytes,
                    currentBytes = currentBytes,
                    maxBytes = maxBytes
                )
            )
        }

        while (currentBytes + tileBytes > maxBytes && entries.isNotEmpty()) {
            val oldestKey = entries.keys.iterator().next()
            val removed = entries.remove(oldestKey)
            if (removed != null) {
                currentBytes = (currentBytes - removed.byteSize).coerceAtLeast(0L)
            }
        }

        entries[key] = tile
        currentBytes += tileBytes
        return Outcome.Success(tile)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0L
    }
}
