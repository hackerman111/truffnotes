package inkspace.media

/** Ephemeral view coordinates only. This module has no document writer. */
data class LaserPoint(val x: Double, val y: Double, val bornNanos: Long)
class LaserOverlay(private val monotonicNanos: () -> Long = System::nanoTime) {
    private val points = ArrayDeque<LaserPoint>()
    @Synchronized fun add(x: Double, y: Double, trail: Boolean) {
        require(x.isFinite() && y.isFinite())
        if (!trail) points.clear()
        points.addLast(LaserPoint(x, y, monotonicNanos()))
        while (points.size > 2048) points.removeFirst()
    }
    @Synchronized fun visible(): List<LaserPoint> {
        val now = monotonicNanos()
        while (points.isNotEmpty() && now - points.first().bornNanos >= 1_500_000_000L) points.removeFirst()
        return points.toList()
    }
    @Synchronized fun clear() = points.clear()
}

typealias TimerSnapshot = inkspace.core.workspace.TimerSnapshot
/** One timestamp, never a scheduled worker: recreating a view cannot start a duplicate clock. */
class TimerAccessory(initial: TimerSnapshot = TimerSnapshot(), private val clock: () -> Long = System::nanoTime) : inkspace.core.workspace.AccessoryTimer {
    private var state = initial.copy(laps = initial.laps.toList())
    @Synchronized override fun elapsed(): Long = state.accumulatedNanos + (state.startedNanos?.let { (clock() - it).coerceAtLeast(0) } ?: 0L)
    @Synchronized override fun start() { if (state.startedNanos == null) state = state.copy(startedNanos = clock()) }
    @Synchronized override fun pause() { state = state.copy(accumulatedNanos = elapsed(), startedNanos = null) }
    @Synchronized override fun reset() {
        val elapsed = elapsed()
        val history = if (elapsed > 0) (state.history + inkspace.core.workspace.TimerSession(elapsed, state.laps.toList())).takeLast(100) else state.history
        state = TimerSnapshot(history = history)
    }
    @Synchronized override fun lap(): Long = elapsed().also { state = state.copy(laps = (state.laps + it).takeLast(100)) }
    @Synchronized override fun snapshot(): TimerSnapshot = state.copy(laps = state.laps.toList())
}
