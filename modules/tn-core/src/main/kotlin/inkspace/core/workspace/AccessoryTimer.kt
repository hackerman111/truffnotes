package inkspace.core.workspace

data class TimerSession(val elapsedNanos: Long, val laps: List<Long>)
data class TimerSnapshot(val accumulatedNanos: Long = 0, val startedNanos: Long? = null, val laps: List<Long> = emptyList(), val history: List<TimerSession> = emptyList())
interface AccessoryTimer {
    fun elapsed(): Long
    fun start()
    fun pause()
    fun reset()
    fun lap(): Long
    fun snapshot(): TimerSnapshot
}
