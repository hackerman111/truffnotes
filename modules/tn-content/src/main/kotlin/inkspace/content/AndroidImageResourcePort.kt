package inkspace.content

import inkspace.core.error.ResourceError
import inkspace.core.outcome.Outcome
import inkspace.core.ports.*

/** Decoded pixels cross the core port only as a bounded immutable TileArtifact. */
class AndroidImageResourcePort(private val decoder: AndroidImageDecoder) : ResourcePort {
    override fun read(request: ResourceRequest): Outcome<ResourceLease,ResourceError> = try {
        require(request.byteBudget >= 12) { "Image raster budget too small" }
        val cap = minOf(request.byteBudget / 3, 8L * 1024 * 1024)
        decoder.decode(request.asset,request.targetWidthPx.coerceIn(1,2048),request.targetHeightPx.coerceIn(1,2048),cap).use { decoded ->
            val tile = decoded.raster(request.asset)
            Outcome.Success(ResourceLease(request.asset,tile.byteSize,true,imageTile=tile))
        }
    } catch (error: Exception) {
        Outcome.Failure(ResourceError.CorruptedResource(request.asset.value,error.message ?: "Image decode failed"))
    }
}
