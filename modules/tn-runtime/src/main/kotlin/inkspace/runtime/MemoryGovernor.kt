package inkspace.runtime

import inkspace.core.error.ResourceError
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.SessionKey
import inkspace.core.outcome.Outcome
import inkspace.core.runtime.MemoryCategory
import inkspace.core.runtime.MemoryPressureLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared bounded worker pool (1..4 threads) owned by the runtime.
 * Prohibits creating threads or executors per feature or per session.
 */
class BoundedWorkerPool(
    requestedThreads: Int = 2
) : Closeable {
    val maxThreads: Int = requestedThreads.coerceIn(1, 4)
    private val threadCounter = AtomicInteger(1)
    private val closed = AtomicBoolean(false)

    private val executor = Executors.newFixedThreadPool(
        maxThreads,
        ThreadFactory { runnable ->
            Thread(runnable, "tn-cpu-worker-${threadCounter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    )

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    fun isClosed(): Boolean = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
        }
    }
}

class MemoryLease internal constructor(
    val category: MemoryCategory,
    val owner: String,
    initialBytes: Long,
    val isEvictableOnTrim: Boolean = false,
    private val onAdjust: (MemoryLease, Long, Long) -> Boolean,
    private val onRelease: (MemoryLease) -> Unit
) : AutoCloseable {
    @Volatile
    var reservedBytes: Long = initialBytes
        private set

    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    fun adjustActualBytes(actualBytes: Long): Boolean {
        require(actualBytes >= 0L) { "actualBytes must be non-negative: $actualBytes" }
        if (closed.get()) return false
        val old = reservedBytes
        if (onAdjust(this, old, actualBytes)) {
            reservedBytes = actualBytes
            return true
        }
        return false
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onRelease(this)
        }
    }
}

class RenderSnapshotPin internal constructor(
    val session: SessionKey,
    val snapshot: DocumentSnapshot,
    private val lease: MemoryLease,
    private val onUnpin: (RenderSnapshotPin) -> Unit
) : AutoCloseable {
    val reservedBytes: Long
        get() = lease.reservedBytes

    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get() || lease.isClosed()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lease.close()
            onUnpin(this)
        }
    }
}

/**
 * Runtime-wide memory admission controller enforcing hard category caps,
 * render snapshot pin limits (max 2), warm session limits (max 3), and cancellation-safe reservations.
 */
class MemoryGovernor(
    val maxRenderSnapshots: Int = 2,
    val maxWarmSessions: Int = 3,
    workerThreads: Int = 2,
    customCaps: Map<MemoryCategory, Long> = emptyMap()
) : Closeable {

    val workerPool = BoundedWorkerPool(workerThreads)

    private val categoryCaps: Map<MemoryCategory, Long> = MemoryCategory.entries.associateWith { cat ->
        customCaps[cat] ?: cat.defaultCapBytes
    }

    private val categoryUsed = HashMap<MemoryCategory, Long>().apply {
        for (cat in MemoryCategory.entries) {
            put(cat, 0L)
        }
    }

    private val activeLeases = LinkedHashSet<MemoryLease>()
    private val renderPins = ArrayDeque<RenderSnapshotPin>()
    private val warmSessions = LinkedHashSet<SessionKey>()

    @Synchronized
    fun capBytes(category: MemoryCategory): Long = categoryCaps[category] ?: category.defaultCapBytes

    @Synchronized
    fun usedBytes(category: MemoryCategory): Long = categoryUsed[category] ?: 0L

    @Synchronized
    fun totalReservedBytes(): Long = categoryUsed.values.sum()

    @Synchronized
    fun activeRenderSnapshotCount(): Int = renderPins.count { !it.isClosed() }

    @Synchronized
    fun activeWarmSessionCount(): Int = warmSessions.size

    @Synchronized
    fun tryReserve(
        category: MemoryCategory,
        bytes: Long,
        owner: String,
        isEvictableOnTrim: Boolean = false
    ): Outcome<MemoryLease, ResourceError> {
        require(bytes >= 0L) { "Reservation bytes must be non-negative: $bytes" }
        val cap = capBytes(category)
        val current = usedBytes(category)
        val projected = current + bytes
        if (projected > cap) {
            return Outcome.Failure(
                ResourceError.ResourceExhausted(
                    "MemoryGovernor rejected $bytes bytes for $owner in $category (current=$current, cap=$cap)"
                )
            )
        }
        categoryUsed[category] = projected
        val lease = MemoryLease(
            category = category,
            owner = owner,
            initialBytes = bytes,
            isEvictableOnTrim = isEvictableOnTrim,
            onAdjust = { targetLease, oldBytes, newBytes ->
                adjustLeaseInternal(targetLease, oldBytes, newBytes)
            },
            onRelease = { releasedLease ->
                releaseLeaseInternal(releasedLease)
            }
        )
        activeLeases.add(lease)
        return Outcome.Success(lease)
    }

    suspend fun <T> withReservation(
        category: MemoryCategory,
        bytes: Long,
        owner: String,
        isEvictableOnTrim: Boolean = false,
        block: suspend (MemoryLease) -> T
    ): Outcome<T, ResourceError> {
        val leaseOutcome = tryReserve(category, bytes, owner, isEvictableOnTrim)
        val lease = when (leaseOutcome) {
            is Outcome.Failure -> return leaseOutcome
            is Outcome.Success -> leaseOutcome.value
        }
        return try {
            Outcome.Success(block(lease))
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            lease.close()
        }
    }

    @Synchronized
    fun pinRenderSnapshot(
        session: SessionKey,
        snapshot: DocumentSnapshot
    ): Outcome<RenderSnapshotPin, ResourceError> {
        // Evict oldest render snapshot pin(s) if we are already at maxRenderSnapshots (2)
        while (renderPins.size >= maxRenderSnapshots) {
            val oldest = renderPins.removeFirst()
            oldest.close()
        }
        val estimated = snapshot.estimatedPinnedBytes().coerceAtLeast(256L)
        val leaseRes = tryReserve(
            category = MemoryCategory.RESIDENT_MODEL_PAYLOAD,
            bytes = estimated,
            owner = "render-snapshot:${session.document.value}:rev-${snapshot.version.revision.value}"
        )
        val lease = when (leaseRes) {
            is Outcome.Failure -> return leaseRes
            is Outcome.Success -> leaseRes.value
        }
        val pin = RenderSnapshotPin(
            session = session,
            snapshot = snapshot,
            lease = lease,
            onUnpin = { unpinned ->
                synchronized(this) {
                    renderPins.remove(unpinned)
                }
            }
        )
        renderPins.addLast(pin)
        return Outcome.Success(pin)
    }

    @Synchronized
    fun touchWarmSession(
        session: SessionKey,
        canEvict: (SessionKey) -> Boolean = { true },
        onEvictSession: (SessionKey) -> Unit = {}
    ) {
        warmSessions.remove(session)
        warmSessions.add(session)
        if (warmSessions.size > maxWarmSessions) {
            val candidate = warmSessions.firstOrNull { it != session && canEvict(it) }
            if (candidate != null) {
                warmSessions.remove(candidate)
                onEvictSession(candidate)
            }
        }
    }

    @Synchronized
    fun removeWarmSession(session: SessionKey) {
        warmSessions.remove(session)
    }

    @Synchronized
    fun trimMemory(
        level: MemoryPressureLevel,
        externalReclaimCallback: () -> Long = { 0L }
    ): Long {
        var reclaimed = 0L
        val evictCategories = when (level) {
            MemoryPressureLevel.LOW -> setOf(MemoryCategory.DERIVED_PATHS_LAYOUT_SPATIAL)
            MemoryPressureLevel.MODERATE, MemoryPressureLevel.CRITICAL -> setOf(
                MemoryCategory.DERIVED_PATHS_LAYOUT_SPATIAL,
                MemoryCategory.CPU_RASTER_TILES
            )
        }
        val toClose = activeLeases.filter { it.isEvictableOnTrim && it.category in evictCategories }
        for (lease in toClose) {
            reclaimed += lease.reservedBytes
            lease.close()
        }
        reclaimed += externalReclaimCallback()
        return reclaimed
    }

    @Synchronized
    private fun adjustLeaseInternal(lease: MemoryLease, oldBytes: Long, newBytes: Long): Boolean {
        if (lease !in activeLeases) return false
        val current = usedBytes(lease.category)
        val cap = capBytes(lease.category)
        val projected = (current - oldBytes + newBytes).coerceAtLeast(0L)
        if (projected > cap) return false
        categoryUsed[lease.category] = projected
        return true
    }

    @Synchronized
    private fun releaseLeaseInternal(lease: MemoryLease) {
        if (activeLeases.remove(lease)) {
            val current = usedBytes(lease.category)
            categoryUsed[lease.category] = (current - lease.reservedBytes).coerceAtLeast(0L)
        }
    }

    override fun close() {
        val snapshotCopy: List<RenderSnapshotPin>
        val leasesCopy: List<MemoryLease>
        synchronized(this) {
            snapshotCopy = renderPins.toList()
            leasesCopy = activeLeases.toList()
        }
        snapshotCopy.forEach { it.close() }
        leasesCopy.forEach { it.close() }
        workerPool.close()
    }
}
