package inkspace.core.error

sealed interface CoreError

sealed interface EditError : CoreError {
    data class PreconditionFailed(val message: String) : EditError
    data class LimitExceeded(val limit: String, val current: Long, val max: Long) : EditError
    data class EntityNotFound(val id: String) : EditError
    data class EntityNotResident(val id: String) : EditError
    data class NonFiniteCoordinate(val coordinate: String) : EditError
    data class CycleDetected(val groupId: String) : EditError
    data class LockedEntity(val id: String) : EditError
    data class StaleRevision(val expected: Long, val actual: Long) : EditError
    data class InvalidPayload(val reason: String) : EditError
    data class NoOpPlan(val reason: String) : EditError
}

data class SerializationError(val message: String, val cause: String? = null) : CoreError

sealed interface GeometryError : CoreError {
    data class ComputationFailed(val reason: String) : GeometryError
}

sealed interface TextError : CoreError {
    data class ShapingFailed(val reason: String) : TextError
}

sealed interface ResourceError : CoreError {
    data class ResourceNotFound(val key: String) : ResourceError
    data class ResourceExhausted(val reason: String) : ResourceError
    data class CorruptedResource(val key: String, val reason: String) : ResourceError
}

sealed interface StorageError : CoreError {
    data class IoFailure(val message: String) : StorageError
    data class TransactionAborted(val reason: String) : StorageError
    data class StaleDurableBase(val expected: Long, val actual: Long) : StorageError
    data class DiskFull(val message: String) : StorageError
    data class MissingAssetReference(val assetId: String) : StorageError
    data class CorruptedDatabase(val reason: String) : StorageError
    data class InvalidWriteSet(val reason: String) : StorageError
}

sealed interface SubmitError : CoreError {
    data class QueueOverflow(val currentDepth: Int, val maxDepth: Int) : SubmitError
    data class ShutdownInProgress(val message: String) : SubmitError
    data class ByteBudgetExceeded(
        val requestedBytes: Long,
        val currentBytes: Long,
        val maxBytes: Long
    ) : SubmitError
    data class StaleSession(val session: String, val reason: String) : SubmitError
    data class StaleHandle(
        val handleId: String,
        val expectedGeneration: Long,
        val actualGeneration: Long
    ) : SubmitError
    data class InvalidPayload(val reason: String) : SubmitError
    data class PendingPersistenceOverflow(
        val pendingCount: Int,
        val pendingBytes: Long
    ) : SubmitError
}

sealed interface StartError : CoreError {
    data class InvalidConfiguration(val reason: String) : StartError
    data class StorageInitFailed(val reason: String) : StartError
    data class AlreadyClosed(val reason: String) : StartError
}
