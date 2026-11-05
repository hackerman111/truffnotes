package inkspace.platform

import inkspace.core.error.SubmitError
import inkspace.core.model.SessionKey
import inkspace.core.outcome.Outcome
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Generation-stamped handle identifying an active platform binding to a document session.
 * Never holds a strong reference to an Android Activity or View.
 */
data class SessionHandle(
    val handleId: String,
    val session: SessionKey,
    val generation: Long
)

/**
 * Thread-safe registry for generation-checked session handles.
 * Rejects stale handles (`stale_handle`), handles repeated closes idempotently (`double_close`),
 * and stores UI contexts exclusively via WeakReference with explicit detach support.
 */
class SessionHandleRegistry {

    private class HandleSlot(
        val session: SessionKey,
        val generation: Long,
        @Volatile var closed: Boolean = false,
        @Volatile var weakContext: WeakReference<Any>?
    )

    private val generationCounter = AtomicLong(0L)
    private val activeSlotBySession = ConcurrentHashMap<SessionKey, HandleSlot>()
    private val slotsByHandleId = ConcurrentHashMap<String, HandleSlot>()

    @Synchronized
    fun issueHandle(session: SessionKey, ownerContext: Any? = null): SessionHandle {
        val nextGen = generationCounter.incrementAndGet()
        val handleId = "${session.document.value}:${session.epoch.value}:gen-$nextGen"
        val slot = HandleSlot(
            session = session,
            generation = nextGen,
            closed = false,
            weakContext = ownerContext?.let { WeakReference(it) }
        )
        activeSlotBySession[session]?.let { previous ->
            previous.closed = true
            previous.weakContext = null
        }
        activeSlotBySession[session] = slot
        slotsByHandleId[handleId] = slot
        return SessionHandle(handleId = handleId, session = session, generation = nextGen)
    }

    @Synchronized
    fun validate(handle: SessionHandle): Outcome<SessionKey, SubmitError> {
        val activeSlot = activeSlotBySession[handle.session]
        val handleSlot = slotsByHandleId[handle.handleId]
        if (handleSlot == null || activeSlot == null) {
            return Outcome.Failure(
                SubmitError.StaleHandle(
                    handleId = handle.handleId,
                    expectedGeneration = activeSlot?.generation ?: -1L,
                    actualGeneration = handle.generation
                )
            )
        }
        if (handleSlot.closed || activeSlot.generation != handle.generation) {
            return Outcome.Failure(
                SubmitError.StaleHandle(
                    handleId = handle.handleId,
                    expectedGeneration = activeSlot.generation,
                    actualGeneration = handle.generation
                )
            )
        }
        return Outcome.Success(handle.session)
    }

    @Synchronized
    fun closeHandle(handle: SessionHandle): Outcome<Boolean, SubmitError> {
        val slot = slotsByHandleId[handle.handleId]
            ?: return Outcome.Success(false)
        if (slot.closed) {
            return Outcome.Success(false)
        }
        slot.closed = true
        slot.weakContext = null
        val currentActive = activeSlotBySession[handle.session]
        if (currentActive != null && currentActive.generation == handle.generation) {
            activeSlotBySession.remove(handle.session)
        }
        return Outcome.Success(true)
    }

    @Synchronized
    fun detachActivityContext(handle: SessionHandle) {
        slotsByHandleId[handle.handleId]?.weakContext = null
    }

    @Synchronized
    fun peekContext(handle: SessionHandle): Any? {
        return slotsByHandleId[handle.handleId]?.weakContext?.get()
    }
}

/**
 * Generation-guarded wrapper for a platform file descriptor or native closeable resource.
 * Guarantees idempotent close (`double_close`) and rejects access after close (`stale_handle`).
 */
class FdResourceHandle(
    val descriptorId: String,
    val generation: Long,
    private val closeable: Closeable
) {

    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    fun <T> useDescriptor(block: () -> T): Outcome<T, SubmitError> {
        if (closed.get()) {
            return Outcome.Failure(
                SubmitError.StaleHandle(
                    handleId = descriptorId,
                    expectedGeneration = generation + 1L,
                    actualGeneration = generation
                )
            )
        }
        return try {
            Outcome.Success(block())
        } catch (t: Throwable) {
            Outcome.Failure(SubmitError.InvalidPayload("Descriptor operation failed: ${t.message}"))
        }
    }

    fun close(): Boolean {
        if (closed.compareAndSet(false, true)) {
            try {
                closeable.close()
            } catch (_: Throwable) {
                // Safe close guard
            }
            return true
        }
        return false
    }
}
