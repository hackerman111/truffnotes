package inkspace.platform

import inkspace.core.error.SubmitError
import inkspace.core.id.DocumentId
import inkspace.core.id.ResizeGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.SurfaceEpoch
import inkspace.core.model.SessionKey
import inkspace.core.outcome.Outcome
import inkspace.core.ports.RequestId
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.AppGateway
import inkspace.core.runtime.ViewportState
import inkspace.core.unit.CameraTransform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lightweight serializable bundle for saving/restoring session & viewport state across Activity recreation
 * or process death without holding any Activity, View, or native handle reference.
 */
@Serializable
data class SavedSessionBundle(
    val documentId: String,
    val sessionEpoch: Long,
    val appliedRevision: Long,
    val durableRevision: Long,
    val cameraScale: Double,
    val cameraTx: Double,
    val cameraTy: Double,
    val widthPx: Int,
    val heightPx: Int,
    val isLandscape: Boolean,
    val isReadOnly: Boolean,
    val isFocusMode: Boolean,
    val surfaceEpoch: Long,
    val resizeGeneration: Long
) {
    fun toJsonString(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJsonString(json: String): SavedSessionBundle =
            Json.decodeFromString(serializer(), json)

        fun fromState(
            session: SessionKey,
            appliedRevision: Revision,
            durableRevision: Revision,
            viewport: ViewportState
        ): SavedSessionBundle {
            return SavedSessionBundle(
                documentId = session.document.value,
                sessionEpoch = session.epoch.value,
                appliedRevision = appliedRevision.value,
                durableRevision = durableRevision.value,
                cameraScale = viewport.camera.scale,
                cameraTx = viewport.camera.translateX,
                cameraTy = viewport.camera.translateY,
                widthPx = viewport.widthPx,
                heightPx = viewport.heightPx,
                isLandscape = viewport.isLandscape,
                isReadOnly = viewport.isReadOnly,
                isFocusMode = viewport.isFocusMode,
                surfaceEpoch = viewport.surfaceEpoch.value,
                resizeGeneration = viewport.resizeGeneration.value
            )
        }
    }

    fun toViewportState(): ViewportState {
        val session = SessionKey(DocumentId(documentId), SessionEpoch(sessionEpoch))
        return ViewportState(
            session = session,
            camera = CameraTransform(scale = cameraScale, translateX = cameraTx, translateY = cameraTy),
            widthPx = widthPx,
            heightPx = heightPx,
            isLandscape = isLandscape,
            isReadOnly = isReadOnly,
            isFocusMode = isFocusMode,
            surfaceEpoch = SurfaceEpoch(surfaceEpoch),
            resizeGeneration = ResizeGeneration(resizeGeneration)
        )
    }
}

/**
 * Coordinates Android Surface lifecycle (`SurfaceEpoch`, `ResizeGeneration`, and acknowledged teardown)
 * with the runtime `AppGateway`.
 * Guarantees a destroyed Surface is never drawn to and teardown is tracked via `SurfaceTeardownAck`.
 */
class SurfaceHost(
    private val gateway: AppGateway
) {
    private var currentEpoch = SurfaceEpoch(0L)
    private var currentResize = ResizeGeneration(0L)
    private var activeSurfaceAttached = false
    private val pendingTeardownEpochs = LinkedHashSet<SurfaceEpoch>()
    private val acknowledgedTeardownEpochs = LinkedHashSet<SurfaceEpoch>()

    @Synchronized
    fun currentSurfaceEpoch(): SurfaceEpoch = currentEpoch

    @Synchronized
    fun currentResizeGeneration(): ResizeGeneration = currentResize

    @Synchronized
    fun isSurfaceActive(): Boolean = activeSurfaceAttached

    @Synchronized
    fun isTeardownAcknowledged(epoch: SurfaceEpoch): Boolean = epoch in acknowledgedTeardownEpochs

    @Synchronized
    fun onSurfaceCreated(
        session: SessionKey,
        widthPx: Int,
        heightPx: Int
    ): Outcome<SurfaceEpoch, SubmitError> {
        currentEpoch = currentEpoch.next()
        currentResize = currentResize.next()
        activeSurfaceAttached = true
        val sendRes = gateway.trySend(
            AppCommand.AttachSurface(
                session = session,
                surfaceEpoch = currentEpoch,
                widthPx = widthPx.coerceAtLeast(1),
                heightPx = heightPx.coerceAtLeast(1),
                resizeGeneration = currentResize
            )
        )
        return when (sendRes) {
            is Outcome.Failure -> {
                activeSurfaceAttached = false
                sendRes
            }
            is Outcome.Success -> Outcome.Success(currentEpoch)
        }
    }

    @Synchronized
    fun onSurfaceChanged(
        session: SessionKey,
        widthPx: Int,
        heightPx: Int
    ): Outcome<ResizeGeneration, SubmitError> {
        if (!activeSurfaceAttached) {
            return Outcome.Failure(
                SubmitError.StaleHandle(
                    handleId = "surface-${session.document.value}",
                    expectedGeneration = currentEpoch.value + 1L,
                    actualGeneration = currentEpoch.value
                )
            )
        }
        currentResize = currentResize.next()
        val sendRes = gateway.trySend(
            AppCommand.AttachSurface(
                session = session,
                surfaceEpoch = currentEpoch,
                widthPx = widthPx.coerceAtLeast(1),
                heightPx = heightPx.coerceAtLeast(1),
                resizeGeneration = currentResize
            )
        )
        return when (sendRes) {
            is Outcome.Failure -> sendRes
            is Outcome.Success -> Outcome.Success(currentResize)
        }
    }

    @Synchronized
    fun onSurfaceDestroyed(session: SessionKey): Outcome<RequestId, SubmitError> {
        if (!activeSurfaceAttached) {
            return Outcome.Success(RequestId(0L))
        }
        activeSurfaceAttached = false
        val closingEpoch = currentEpoch
        pendingTeardownEpochs.add(closingEpoch)
        return gateway.trySend(
            AppCommand.DetachSurface(
                session = session,
                surfaceEpoch = closingEpoch
            )
        )
    }

    @Synchronized
    fun onRuntimeEvents(events: List<AppEvent>) {
        for (ev in events) {
            if (ev is AppEvent.SurfaceTeardownAck) {
                pendingTeardownEpochs.remove(ev.surfaceEpoch)
                acknowledgedTeardownEpochs.add(ev.surfaceEpoch)
            }
        }
    }
}
