package inkspace.app

import android.view.MotionEvent
import inkspace.core.error.StartError
import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.model.SessionKey
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
import inkspace.runtime.WorkspaceGateway
import inkspace.core.workspace.NotebookGateway
import java.io.File
import inkspace.ui.BaselineSessionController

/**
 * Composition root container wiring `Runtime`, `SurfaceHost`, `InputTransport`, and `BaselineSessionController`.
 * Owns no Activity or View references across configuration changes.
 */
class TruffnotesAppContainer private constructor(
    val runtime: Runtime,
    val surfaceHost: SurfaceHost,
    val inputTransport: InputTransport,
    val controller: BaselineSessionController,
    val notebooks: NotebookGateway
) {
    fun resolveActiveSession(): SessionKey? {
        val uiSession = controller.uiState.activeSession
        if (uiSession != null) return uiSession
        val activeDoc = notebooks.workspace().active?.documentId ?: return null
        return runtime.activeSessionFor(activeDoc)
    }

    fun bindDrawingSurface(surface: android.view.Surface) {
        resolveActiveSession()?.let { runtime.bindDrawingSurface(it, surface) }
    }
    fun createTextEditorView(context: android.content.Context, id: String?): android.view.View? =
        resolveActiveSession()?.let { runtime.createTextEditorView(context, it, id) }

    fun syncEvents(maxBatch: Int = 64): List<AppEvent> {
        val events = controller.syncEvents(maxBatch)
        surfaceHost.onRuntimeEvents(events)
        (notebooks as? WorkspaceGateway)?.onRuntimeEvents(events)
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

    private var surfaceSession: SessionKey? = null
    private val surfaceEpochs = mutableMapOf<SessionKey, SurfaceEpoch>()

    fun onSurfaceAttached(widthPx: Int, heightPx: Int): Outcome<SurfaceEpoch, SubmitError> {
        val session = resolveActiveSession()
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        val attached = surfaceHost.onSurfaceCreated(session, widthPx, heightPx)
        if (attached is Outcome.Success) {
            surfaceSession = session
            surfaceEpochs[session] = attached.value
        }
        return attached
    }

    fun onSurfaceChanged(widthPx: Int, heightPx: Int): Outcome<ResizeGeneration, SubmitError> {
        val session = resolveActiveSession()
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        return surfaceHost.onSurfaceChanged(session, widthPx, heightPx)
    }

    fun onSurfaceDestroyed(session: SessionKey? = surfaceSession): SurfaceEpoch? {
        if (session == null) return null
        val ownedEpoch = surfaceEpochs.remove(session) ?: return null
        runtime.unbindDrawingSurface(session)
        if (session != surfaceSession) {
            runtime.trySend(AppCommand.DetachSurface(session, ownedEpoch))
            return ownedEpoch
        }
        if (!surfaceHost.isSurfaceActive()) return null
        val closingEpoch = surfaceHost.currentSurfaceEpoch()
        surfaceHost.onSurfaceDestroyed(session)
        surfaceSession = null
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
        val session = resolveActiveSession()
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
        val session = resolveActiveSession()
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
        val session = resolveActiveSession()
            ?: return Outcome.Failure(SubmitError.StaleSession("none", "No active session"))
        val actionMasked = event.actionMasked
        if (actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount > 1) {
            var lastOutcome: Outcome<RequestId, SubmitError> = Outcome.Success(RequestId(0))
            for (p in 0 until event.pointerCount) {
                val leasedOutcome = inputTransport.extractInkBatchFromMotionEvent(session, event, pointerIndex = p)
                val leased = when (leasedOutcome) {
                    is Outcome.Failure -> return leasedOutcome
                    is Outcome.Success -> leasedOutcome.value
                }
                try {
                    val dispatchRes = controller.dispatchInputBatch(leased.leasedBatch.batch)
                    if (dispatchRes is Outcome.Failure) return dispatchRes
                    val inkRes = runtime.submitInkBatch(leased.inkBatch)
                    if (inkRes is Outcome.Failure) return inkRes
                    lastOutcome = inkRes
                } finally {
                    leased.close()
                }
            }
            return lastOutcome
        }
        val leasedOutcome = inputTransport.extractInkBatchFromMotionEvent(session, event)
        val leased = when (leasedOutcome) {
            is Outcome.Failure -> return leasedOutcome
            is Outcome.Success -> leasedOutcome.value
        }
        return try {
            val dispatchRes = controller.dispatchInputBatch(leased.leasedBatch.batch)
            if (dispatchRes is Outcome.Failure) {
                dispatchRes
            } else {
                runtime.submitInkBatch(leased.inkBatch)
            }
        } finally {
            leased.close()
        }
    }

    fun closeAsync(): Outcome<RequestId, SubmitError> {
        notebooks.close()
        return runtime.closeAsync()
    }

    companion object {
        fun create(
            config: RuntimeConfig,
            ports: PlatformPorts = PlatformPorts()
        ): Outcome<TruffnotesAppContainer, StartError> {
            return when (val started = Runtime.start(config, ports)) {
                is Outcome.Failure -> started
                is Outcome.Success -> {
                    val rt = started.value
                    val controller = BaselineSessionController(rt)
                    Outcome.Success(
                        TruffnotesAppContainer(
                            runtime = rt,
                            surfaceHost = SurfaceHost(rt),
                            inputTransport = InputTransport(poolSize = 64, maxSamplesPerBatch = 512),
                            controller = controller,
                            notebooks = WorkspaceGateway(File(config.storageRootPath, "library.json"), rt,
                                featureDispatch=rt::performNotebookAction,featureState=rt::notebookState,
                                thumbnailProvider=rt::notebookThumbnail,copyNotebook=rt::copyNotebook) { controller.uiState.activeSession }
                        )
                    )
                }
            }
        }
    }
}
