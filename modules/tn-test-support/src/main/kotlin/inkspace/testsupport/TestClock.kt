package inkspace.testsupport

import java.util.concurrent.atomic.AtomicLong

class TestClock(initialNanos: Long = 1_000_000_000L) {
    private val currentNanos = AtomicLong(initialNanos)

    fun nowNanos(): Long = currentNanos.get()

    fun nowMillis(): Long = currentNanos.get() / 1_000_000L

    fun advanceNanos(deltaNanos: Long): Long {
        require(deltaNanos >= 0L) { "Cannot advance clock backwards: $deltaNanos" }
        return currentNanos.addAndGet(deltaNanos)
    }

    fun advanceMillis(deltaMs: Long): Long = advanceNanos(deltaMs * 1_000_000L)
}
