package inkspace.runtime

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit thread-confinement verifier for actor-owned mutable state (Engine, Render, Store).
 * Never relies on language types alone to prevent cross-thread access.
 */
class OwnerGuard(val roleName: String) {
    private val ownerThreadId = AtomicLong(-1L)

    fun bindCurrentThread() {
        val currentId = Thread.currentThread().id
        val prev = ownerThreadId.get()
        check(prev == -1L || prev == currentId) {
            "OwnerGuard[$roleName] already bound to thread $prev, cannot rebind to $currentId"
        }
        ownerThreadId.set(currentId)
    }

    fun requireOwnerThread() {
        val expected = ownerThreadId.get()
        val actual = Thread.currentThread().id
        check(expected == -1L || expected == actual) {
            "Confinement violation in $roleName: expected owner thread $expected, but accessed on thread $actual (${Thread.currentThread().name})"
        }
    }
}

/**
 * Single-thread application-owned executor with explicit owner guard and structured shutdown.
 */
class ConfinedSingleThreadExecutor(
    val name: String
) : Closeable {
    val guard = OwnerGuard(name)
    private val closed = AtomicBoolean(false)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(
                {
                    guard.bindCurrentThread()
                    runnable.run()
                },
                name
            ).apply {
                isDaemon = true
            }
        }
    )

    fun isClosed(): Boolean = closed.get()

    fun execute(task: () -> Unit) {
        if (!closed.get()) {
            executor.execute {
                guard.requireOwnerThread()
                task()
            }
        }
    }

    fun <T> submit(task: () -> T): Future<T> {
        return executor.submit(
            Callable {
                guard.requireOwnerThread()
                task()
            }
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown()
        }
    }
}
