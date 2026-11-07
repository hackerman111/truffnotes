package inkspace.ui

import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.SessionKey
import inkspace.core.mutation.EditPlan
import inkspace.core.outcome.Outcome
import inkspace.core.ports.RequestId
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.AppGateway
import inkspace.core.runtime.ImmutableInputBatch
import inkspace.core.runtime.SaveStatus
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.runtime.ViewportState
import kotlin.math.ceil

data class CallbackDurationStats(
    val sampleCount: Int,
    val minMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val samplesMs: List<Double>
)

/**
 * Tracks UI/input callback execution durations in nanoseconds and computes quantiles
 * using the frozen `nearest_rank_inclusive_sorted_1_indexed` formula from `docs/perf/protocol.json`.
 */
class CallbackDurationTracker(
    private val maxSamples: Int = 2048
) {
    private val durationsNanos = LongArray(maxSamples)
    private var count = 0
    private var writeIndex = 0

    @Synchronized
    fun recordDurationNanos(nanos: Long) {
        durationsNanos[writeIndex] = nanos.coerceAtLeast(0L)
        writeIndex = (writeIndex + 1) % maxSamples
        if (count < maxSamples) {
            count += 1
        }
    }

    @Synchronized
    fun reset() {
        count = 0
        writeIndex = 0
    }

    @Synchronized
    fun computeQuantilesMs(): CallbackDurationStats {
        if (count == 0) {
            return CallbackDurationStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
        }
        val active = LongArray(count)
        for (i in 0 until count) {
            active[i] = durationsNanos[i]
        }
        active.sort()
        val samplesMs = active.map { it.toDouble() / 1_000_000.0 }

        fun rankValue(p: Double): Double {
            val rankIndex = (ceil(p * count).toInt() - 1).coerceIn(0, count - 1)
            return samplesMs[rankIndex]
        }

        return CallbackDurationStats(
            sampleCount = count,
            minMs = samplesMs.first(),
            p50Ms = rankValue(0.50),
            p95Ms = rankValue(0.95),
            p99Ms = rankValue(0.99),
            maxMs = samplesMs.last(),
            samplesMs = samplesMs
        )
    }
}

data class BaselineUiState(
    val activeSession: SessionKey? = null,
    val snapshot: DocumentSnapshot? = null,
    val saveStatus: SaveStatus? = null,
    val viewport: ViewportState? = null,
    val isLoading: Boolean = false,
    val isInputBlockedByOverflow: Boolean = false,
    val activeErrors: List<AppEvent.ErrorEvent> = emptyList(),
    val isClosed: Boolean = false
)

/**
 * Non-blocking UI session controller connecting Compose screens to the core `AppGateway`.
 * Rejects input when persistence queues report `PENDING_OVERFLOW` and profiles callback durations (`p95 <= 1 ms`).
 */
class BaselineSessionController(
    val gateway: AppGateway,
    val callbackTracker: CallbackDurationTracker = CallbackDurationTracker()
) {
    @Volatile
    var uiState: BaselineUiState = BaselineUiState()
        private set

    fun openDocument(
        documentId: DocumentId,
        initialViewport: ViewportState? = null
    ): Outcome<RequestId, SubmitError> {
        val startNs = System.nanoTime()
        uiState = uiState.copy(isLoading = true)
        val res = gateway.trySend(
            AppCommand.OpenSession(
                documentId = documentId,
                createIfMissing = true,
                initialViewport = initialViewport
            )
        )
        callbackTracker.recordDurationNanos(System.nanoTime() - startNs)
        return res
    }

    fun dispatchInputBatch(batch: ImmutableInputBatch): Outcome<RequestId, SubmitError> {
        val startNs = System.nanoTime()
        val currentState = uiState
        if (currentState.isInputBlockedByOverflow) {
            val status = currentState.saveStatus
            callbackTracker.recordDurationNanos(System.nanoTime() - startNs)
            return Outcome.Failure(
                SubmitError.PendingPersistenceOverflow(
                    pendingCount = status?.pendingCount ?: 0,
                    pendingBytes = status?.pendingBytes ?: 0L
                )
            )
        }
        val res = gateway.trySend(AppCommand.SubmitInputBatch(batch))
        callbackTracker.recordDurationNanos(System.nanoTime() - startNs)
        return res
    }

    fun dispatchEdit(plan: EditPlan, estimatedBytes: Long = 1024L): Outcome<RequestId, SubmitError> {
        val startNs = System.nanoTime()
        val currentState = uiState
        val session = currentState.activeSession
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        if (currentState.isInputBlockedByOverflow || currentState.viewport?.isReadOnly == true) {
            val status = currentState.saveStatus
            callbackTracker.recordDurationNanos(System.nanoTime() - startNs)
            return Outcome.Failure(
                SubmitError.PendingPersistenceOverflow(
                    pendingCount = status?.pendingCount ?: 0,
                    pendingBytes = status?.pendingBytes ?: 0L
                )
            )
        }
        val res = gateway.trySend(
            AppCommand.SubmitEdit(
                session = session,
                plan = plan,
                estimatedBytes = estimatedBytes
            )
        )
        callbackTracker.recordDurationNanos(System.nanoTime() - startNs)
        return res
    }

    fun updateViewport(transform: (ViewportState) -> ViewportState): Outcome<RequestId, SubmitError> {
        val startNs = System.nanoTime()
        val currentVp = uiState.viewport
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active viewport"))
        val updated = transform(currentVp)
        val res = gateway.trySend(AppCommand.UpdateViewport(updated))
        if (res.isSuccess) {
            uiState = uiState.copy(viewport = updated)
        }
        callbackTracker.recordDurationNanos(System.nanoTime() - startNs)
        return res
    }

    fun dismissError(error: AppEvent.ErrorEvent) {
        gateway.acknowledgeError(error)
        uiState = uiState.copy(activeErrors = uiState.activeErrors.filterNot { it == error })
    }

    fun syncEvents(maxBatch: Int = 64): List<AppEvent> {
        val startNs = System.nanoTime()
        val batch = gateway.pollEvents(maxBatch)
        var nextState = uiState

        for (ev in batch.events) {
            when (ev) {
                is AppEvent.SessionOpened -> {
                    nextState = nextState.copy(
                        activeSession = ev.session,
                        snapshot = ev.snapshot,
                        saveStatus = ev.saveStatus,
                        viewport = ev.viewport,
                        isLoading = false,
                        isInputBlockedByOverflow = ev.saveStatus.status == SaveStatusKind.PENDING_OVERFLOW
                    )
                }
                is AppEvent.SnapshotPublished -> {
                    if (ev.session == nextState.activeSession) {
                        nextState = nextState.copy(
                            snapshot = ev.snapshot,
                            saveStatus = ev.saveStatus,
                            isInputBlockedByOverflow = ev.saveStatus.status == SaveStatusKind.PENDING_OVERFLOW
                        )
                    }
                }
                is AppEvent.SaveStatusChanged -> {
                    if (ev.saveStatus.session == nextState.activeSession) {
                        nextState = nextState.copy(
                            saveStatus = ev.saveStatus,
                            isInputBlockedByOverflow = ev.saveStatus.status == SaveStatusKind.PENDING_OVERFLOW
                        )
                    }
                }
                is AppEvent.ViewportChanged -> {
                    if (ev.viewport.session == nextState.activeSession) {
                        nextState = nextState.copy(viewport = ev.viewport)
                    }
                }
                is AppEvent.SessionClosed -> {
                    if (ev.session == nextState.activeSession) {
                        nextState = nextState.copy(activeSession = null, isLoading = false)
                    }
                }
                is AppEvent.Closed -> {
                    nextState = nextState.copy(isClosed = true, isLoading = false)
                }
                else -> {}
            }
        }
        nextState = nextState.copy(activeErrors = batch.errorEvents)
        uiState = nextState
        callbackTracker.recordDurationNanos(System.nanoTime() - startNs)
        return batch.events
    }
}
