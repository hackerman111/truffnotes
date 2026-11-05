package inkspace.app

import android.view.MotionEvent
import inkspace.core.error.StartError
import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.ResizeGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SurfaceEpoch
import inkspace.core.outcome.Outcome
import inkspace.core.ports.RequestId
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.MemoryPressureLevel
import inkspace.core.runtime.PlatformPorts
import inkspace.core.runtime.RuntimeConfig
import inkspace.platform.InputTransport
import inkspace.platform.SavedSessionBundle
import inkspace.platform.SurfaceHost
import inkspace.runtime.Runtime
import inkspace.ui.BaselineSessionController

/**
 * Composition root container wiring `Runtime`, `SurfaceHost`, `InputTransport`, and `BaselineSessionController`.
 * Owns no Activity or View references across configuration changes.
 */
class TruffnotesAppContainer private constructor(
    val runtime: Runtime,
    val surfaceHost: SurfaceHost,
    val inputTransport: InputTransport,
    val controller: BaselineSessionController
) {
    fun syncEvents(maxBatch: Int = 64): List<AppEvent> {
        val events = controller.syncEvents(maxBatch)
        surfaceHost.onRuntimeEvents(events)
        return events
    }

    fun openOrRestoreSession(
        defaultDocumentId: DocumentId,
        savedBundle: SavedSessionBundle? = null
    ): Outcome<RequestId, SubmitError> {
        val targetDocId = if (savedBundle != null) {
            DocumentId(savedBundle.documentId)
        } else {
            defaultDocumentId
        }
        val initialViewport = savedBundle?.toViewportState()
        return controller.openDocument(targetDocId, initialViewport = initialViewport)
    }

    fun onSurfaceAttached(widthPx: Int, heightPx: Int): Outcome<SurfaceEpoch, SubmitError> {
        val session = controller.uiState.activeSession
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        return surfaceHost.onSurfaceCreated(session, widthPx, heightPx)
    }

    fun onSurfaceChanged(widthPx: Int, heightPx: Int): Outcome<ResizeGeneration, SubmitError> {
        val session = controller.uiState.activeSession
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        return surfaceHost.onSurfaceChanged(session, widthPx, heightPx)
    }

    fun onSurfaceDestroyed(): SurfaceEpoch? {
        val session = controller.uiState.activeSession ?: return null
        if (!surfaceHost.isSurfaceActive()) return null
        val closingEpoch = surfaceHost.currentSurfaceEpoch()
        surfaceHost.onSurfaceDestroyed(session)
        return closingEpoch
    }

    fun onSaveInstanceState(): SavedSessionBundle? {
        val state = controller.uiState
        val session = state.activeSession ?: return null
        val viewport = state.viewport ?: return null
        val saveStatus = state.saveStatus
        return SavedSessionBundle.fromState(
            session = session,
            appliedRevision = saveStatus?.appliedRevision ?: Revision(0L),
            durableRevision = saveStatus?.durableRevision ?: Revision(0L),
            viewport = viewport
        )
    }

    fun onBackgroundFlush(): Outcome<RequestId, SubmitError> {
        val session = controller.uiState.activeSession
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        return runtime.trySend(AppCommand.BackgroundFlush(session))
    }

    fun onTrimMemory(level: MemoryPressureLevel): Outcome<RequestId, SubmitError> {
        return runtime.trySend(AppCommand.TrimMemory(level))
    }

    fun submitRawMotionBatch(
        pointerId: Int,
        action: InputActionKind,
        count: Int,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray,
        tilts: FloatArray,
        timestampsMs: LongArray
    ): Outcome<RequestId, SubmitError> {
        val session = controller.uiState.activeSession
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        val seq = inputTransport.nextSequenceNumber()
        val bufOutcome = inputTransport.acquireBuffer(session, pointerId, seq, action)
        val buf = when (bufOutcome) {
            is Outcome.Failure -> return bufOutcome
            is Outcome.Success -> bufOutcome.value
        }

        for (i in 0 until count) {
            val appendRes = buf.appendSample(
                x = xs[i],
                y = ys[i],
                pressure = pressures[i],
                tilt = tilts[i],
                timestampMs = timestampsMs[i]
            )
            if (appendRes is Outcome.Failure) {
                inputTransport.cancelBuffer(buf)
                return appendRes
            }
        }

        val leasedOutcome = inputTransport.handoff(buf)
        val leased = when (leasedOutcome) {
            is Outcome.Failure -> return leasedOutcome
            is Outcome.Success -> leasedOutcome.value
        }

        return try {
            controller.dispatchInputBatch(leased.batch)
        } finally {
            leased.close()
        }
    }

    fun submitMotionEvent(event: MotionEvent): Outcome<RequestId, SubmitError> {
        val session = controller.uiState.activeSession
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        val leasedOutcome = inputTransport.extractFromMotionEvent(session, event)
        val leased = when (leasedOutcome) {
            is Outcome.Failure -> return leasedOutcome
            is Outcome.Success -> leasedOutcome.value
        }
        return try {
            controller.dispatchInputBatch(leased.batch)
        } finally {
            leased.close()
        }
    }

    fun closeAsync(): Outcome<RequestId, SubmitError> = runtime.closeAsync()

    companion object {
        fun create(
            config: RuntimeConfig,
            ports: PlatformPorts = PlatformPorts()
        ): Outcome<TruffnotesAppContainer, StartError> {
            return when (val started = Runtime.start(config, ports)) {
                is Outcome.Failure -> started
                is Outcome.Success -> {
                    val rt = started.value
                    Outcome.Success(
                        TruffnotesAppContainer(
                            runtime = rt,
                            surfaceHost = SurfaceHost(rt),
                            inputTransport = InputTransport(poolSize = 64, maxSamplesPerBatch = 512),
                            controller = BaselineSessionController(rt)
                        )
                    )
                }
            }
        }
    }
}
