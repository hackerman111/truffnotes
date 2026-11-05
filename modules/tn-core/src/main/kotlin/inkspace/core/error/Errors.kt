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

sealed interface RenderError : CoreError {
    data class WrongThread(val expectedThread: String, val actualThread: String) : RenderError
    data class InvalidSurface(val surfaceEpoch: Long, val reason: String) : RenderError
    data class InvalidResize(val widthPx: Int, val heightPx: Int, val reason: String) : RenderError
    data class MemoryBudgetExceeded(
        val category: String,
        val requestedBytes: Long,
        val currentBytes: Long,
        val maxBytes: Long
    ) : RenderError
    data class GeometryFailure(val strokeId: String, val reason: String) : RenderError
    data class TextLayoutFailure(val objectId: String, val reason: String) : RenderError
    data class ResourceFailure(val assetId: String, val reason: String) : RenderError
}

sealed interface InputError : CoreError {
    data class InvalidSample(val index: Int, val reason: String) : InputError
    data class StaleSequence(val pointerId: Int, val expectedMinSeq: Long, val actualSeq: Long) : InputError
    data class PalmRejected(val pointerId: Int, val reason: String) : InputError
    data class StylusOnlyIgnored(val pointerId: Int, val reason: String) : InputError
    data class LockedTarget(val layerId: String, val reason: String) : InputError
    data class SpoolFailed(val strokeId: String, val reason: String) : InputError
    data class MemoryWindowExceeded(val requestedBytes: Long, val maxWindowBytes: Long) : InputError
    data class SessionNotActive(val session: String, val reason: String) : InputError
}
