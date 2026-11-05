package inkspace.core.limits

import kotlinx.serialization.Serializable

@Serializable
data class Limits(
    val maxObjectsPerSpace: Int = 10_000,
    val maxGroupDepth: Int = 32,
    val maxPayloadBytes: Long = 100_000_000L,
    val maxStrokeSamplesPerChunk: Int = 64_000,
    val maxTextChars: Int = 1_000_000
) {
    init {
        require(maxObjectsPerSpace > 0) { "maxObjectsPerSpace must be positive" }
        require(maxGroupDepth > 0) { "maxGroupDepth must be positive" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        require(maxStrokeSamplesPerChunk > 0) { "maxStrokeSamplesPerChunk must be positive" }
        require(maxTextChars > 0) { "maxTextChars must be positive" }
    }
}
