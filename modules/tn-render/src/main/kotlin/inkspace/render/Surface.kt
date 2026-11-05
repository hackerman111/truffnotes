package inkspace.render

import android.os.Debug
import inkspace.core.error.RenderError
import inkspace.core.id.SurfaceEpoch
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.SessionKey
import inkspace.core.outcome.Outcome
import inkspace.core.runtime.MemoryCategory

/**
 * Manages render surface epochs, acknowledged surface teardown, controlled graphics target budgets
 * (`CONTROLLED_GRAPHICS_TARGETS`, default 96 MiB), and pinned render snapshots (max 2 per session).
 * Explicitly separates application-controlled graphics estimates from Android SDK / native heap counters.
 */
class RenderSurfaceManager(
    val maxControlledGraphicsBytes: Long = MemoryCategory.CONTROLLED_GRAPHICS_TARGETS.defaultCapBytes,
    val maxPinnedSnapshotsPerSession: Int = 2
) {
    private val releasedSurfaceEpochs = LinkedHashSet<SurfaceEpoch>()
    private val pinnedSnapshotsBySession = LinkedHashMap<SessionKey, ArrayDeque<DocumentSnapshot>>()
    private var activeControlledBytes: Long = 0L

    @Synchronized
    fun isSurfaceReleased(epoch: SurfaceEpoch): Boolean = epoch in releasedSurfaceEpochs

    @Synchronized
    fun releaseSurface(epoch: SurfaceEpoch): Boolean {
        return releasedSurfaceEpochs.add(epoch)
    }

    @Synchronized
    fun validateSurfaceAndAdmitTarget(
        surfaceEpoch: SurfaceEpoch,
        widthPx: Int,
        heightPx: Int,
        needsOffscreenBuffer: Boolean
    ): Outcome<Long, RenderError> {
        if (surfaceEpoch.value <= 0L || surfaceEpoch in releasedSurfaceEpochs) {
            return Outcome.Failure(
                RenderError.InvalidSurface(
                    surfaceEpoch = surfaceEpoch.value,
                    reason = "SurfaceEpoch ${surfaceEpoch.value} is invalid or already released"
                )
            )
        }
        if (widthPx <= 0 || heightPx <= 0) {
            return Outcome.Failure(
                RenderError.InvalidResize(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    reason = "Target dimensions must be positive: ($widthPx, $heightPx)"
                )
            )
        }

        val frameBufferBytes = widthPx.toLong() * heightPx.toLong() * 4L
        val requiredBytes = if (needsOffscreenBuffer) frameBufferBytes * 2L else frameBufferBytes
        if (requiredBytes > maxControlledGraphicsBytes) {
            return Outcome.Failure(
                RenderError.MemoryBudgetExceeded(
                    category = MemoryCategory.CONTROLLED_GRAPHICS_TARGETS.name,
                    requestedBytes = requiredBytes,
                    currentBytes = activeControlledBytes,
                    maxBytes = maxControlledGraphicsBytes
                )
            )
        }
        activeControlledBytes = requiredBytes
        return Outcome.Success(requiredBytes)
    }

    @Synchronized
    fun controlledGraphicsBytes(): Long = activeControlledBytes

    fun sdkReportedGraphicsBytes(): Long {
        return runCatching { Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L) }.getOrDefault(0L)
    }

    @Synchronized
    fun pinSnapshot(session: SessionKey, snapshot: DocumentSnapshot): Boolean {
        val deque = pinnedSnapshotsBySession.getOrPut(session) { ArrayDeque(maxPinnedSnapshotsPerSession) }
        while (deque.size >= maxPinnedSnapshotsPerSession) {
            deque.removeFirst()
        }
        deque.addLast(snapshot)
        return true
    }

    @Synchronized
    fun unpinSessionSnapshots(session: SessionKey) {
        pinnedSnapshotsBySession.remove(session)
    }

    @Synchronized
    fun pinnedSnapshotsCount(session: SessionKey? = null): Int {
        if (session != null) {
            return pinnedSnapshotsBySession[session]?.size ?: 0
        }
        return pinnedSnapshotsBySession.values.sumOf { it.size }
    }

    @Synchronized
    fun clear() {
        pinnedSnapshotsBySession.clear()
        activeControlledBytes = 0L
    }
}
