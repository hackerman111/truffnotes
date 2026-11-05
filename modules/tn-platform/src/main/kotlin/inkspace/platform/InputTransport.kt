package inkspace.platform

import android.view.InputDevice
import android.view.MotionEvent
import inkspace.core.error.SubmitError
import inkspace.core.ink.InputBatch
import inkspace.core.ink.InputToolType
import inkspace.core.ink.PredictedSample
import inkspace.core.model.SessionKey
import inkspace.core.outcome.Outcome
import inkspace.core.runtime.ImmutableInputBatch
import inkspace.core.runtime.InputActionKind
import inkspace.core.unit.MAX_WORLD_COORDINATE_PT
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Hardware axis support descriptor for an input device/pointer.
 * Prevents fabricating fake pressure or tilt readings when the device/tool does not report them.
 */
data class DeviceAxisCapabilities(
    val supportsPressure: Boolean,
    val supportsTilt: Boolean
) {
    companion object {
        fun fromInputDevice(device: InputDevice?, toolType: InputToolType): DeviceAxisCapabilities {
            if (toolType == InputToolType.FINGER) {
                // Passive capacitive stylus / finger touch is honestly reported without synthetic pressure/tilt
                return DeviceAxisCapabilities(supportsPressure = false, supportsTilt = false)
            }
            if (device == null) {
                val isActiveStylus = toolType == InputToolType.STYLUS || toolType == InputToolType.ERASER
                return DeviceAxisCapabilities(
                    supportsPressure = isActiveStylus,
                    supportsTilt = isActiveStylus
                )
            }
            val hasPressure = device.getMotionRange(MotionEvent.AXIS_PRESSURE) != null
            val hasTilt = device.getMotionRange(MotionEvent.AXIS_TILT) != null
            return DeviceAxisCapabilities(
                supportsPressure = hasPressure,
                supportsTilt = hasTilt
            )
        }
    }
}

/**
 * Lease wrapping a handed-off [LeasedInputBatch] together with its typed [InputBatch] for `:tn-ink`.
 */
class LeasedInkInputBatch internal constructor(
    val leasedBatch: LeasedInputBatch,
    val inkBatch: InputBatch
) : AutoCloseable {
    fun isClosed(): Boolean = leasedBatch.isClosed()

    override fun close() {
        leasedBatch.close()
    }
}

/**
 * Lease wrapping a handed-off ImmutableInputBatch together with its pooled slot release callback.
 * Only when closed (consumed or canceled) is the underlying pool slot eligible for reuse under a new generation.
 */
class LeasedInputBatch internal constructor(
    val batch: ImmutableInputBatch,
    val slotIndex: Int,
    val generation: Long,
    private val onRelease: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            onRelease()
        }
    }
}

/**
 * Pooled primitive sample buffer token.
 * Rejects any mutation once handed off (`mutate_after_handoff`) or canceled (`pool_reuse_after_cancel`).
 */
class PooledInputBuffer internal constructor(
    val slotIndex: Int,
    val generation: Long,
    val session: SessionKey,
    val pointerId: Int,
    val sequenceNumber: Long,
    val action: InputActionKind,
    private val slot: InputTransport.PoolSlot,
    private val maxBatchBytes: Int
) {
    fun appendSample(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        timestampMs: Long
    ): Outcome<Int, SubmitError> {
        return slot.appendSample(
            callerGeneration = generation,
            x = x,
            y = y,
            pressure = pressure,
            tilt = tilt,
            timestampMs = timestampMs,
            maxBatchBytes = maxBatchBytes
        )
    }

    fun overwriteSampleForTest(
        index: Int,
        x: Float,
        y: Float
    ): Outcome<Unit, SubmitError> {
        return slot.overwriteSample(callerGeneration = generation, index = index, x = x, y = y)
    }
}

/**
 * Bounded pool of primitive sample buffers for zero-Point-allocation input transport.
 * Validates finite coordinates, pressure bounds [0, 1], monotonic timestamps, and <= 64 KiB batch limit.
 */
class InputTransport(
    val poolSize: Int = 64,
    val maxSamplesPerBatch: Int = 2700,
    val maxBatchBytes: Int = 64 * 1024
) {
    init {
        require(poolSize > 0) { "poolSize must be positive" }
        require(maxSamplesPerBatch > 0) { "maxSamplesPerBatch must be positive" }
        require(maxBatchBytes > 0) { "maxBatchBytes must be positive" }
    }

    internal enum class SlotState {
        FREE,
        WRITING,
        HANDED_OFF
    }

    internal class PoolSlot(
        val slotIndex: Int,
        val capacity: Int
    ) {
        var generation: Long = 0L
        var state: SlotState = SlotState.FREE
        var count: Int = 0
        var lastTimestampMs: Long = Long.MIN_VALUE
        val xs = FloatArray(capacity)
        val ys = FloatArray(capacity)
        val pressures = FloatArray(capacity)
        val tilts = FloatArray(capacity)
        val timestampsMs = LongArray(capacity)

        @Synchronized
        fun acquire(): Long? {
            if (state != SlotState.FREE) return null
            generation += 1L
            state = SlotState.WRITING
            count = 0
            lastTimestampMs = Long.MIN_VALUE
            return generation
        }

        @Synchronized
        fun appendSample(
            callerGeneration: Long,
            x: Float,
            y: Float,
            pressure: Float,
            tilt: Float,
            timestampMs: Long,
            maxBatchBytes: Int
        ): Outcome<Int, SubmitError> {
            if (state != SlotState.WRITING || generation != callerGeneration) {
                return Outcome.Failure(
                    SubmitError.StaleHandle(
                        handleId = "input-slot-$slotIndex",
                        expectedGeneration = generation,
                        actualGeneration = callerGeneration
                    )
                )
            }
            if (!x.isFinite() || !y.isFinite() || !pressure.isFinite() || !tilt.isFinite()) {
                return Outcome.Failure(SubmitError.InvalidPayload("Non-finite input sample value"))
            }
            if (abs(x.toDouble()) > MAX_WORLD_COORDINATE_PT || abs(y.toDouble()) > MAX_WORLD_COORDINATE_PT) {
                return Outcome.Failure(SubmitError.InvalidPayload("Input sample coordinate exceeds world envelope"))
            }
            if (pressure < 0.0f || pressure > 1.0f) {
                return Outcome.Failure(SubmitError.InvalidPayload("Input sample pressure $pressure out of [0, 1]"))
            }
            if (timestampMs < lastTimestampMs) {
                return Outcome.Failure(
                    SubmitError.InvalidPayload("Non-monotonic timestamp: $timestampMs < $lastTimestampMs")
                )
            }
            val nextCount = count + 1
            val nextBytes = ImmutableInputBatch.BATCH_HEADER_BYTES + nextCount * ImmutableInputBatch.BYTES_PER_SAMPLE
            if (nextCount > capacity || nextBytes > maxBatchBytes) {
                return Outcome.Failure(
                    SubmitError.ByteBudgetExceeded(
                        requestedBytes = nextBytes.toLong(),
                        currentBytes = (ImmutableInputBatch.BATCH_HEADER_BYTES + count * ImmutableInputBatch.BYTES_PER_SAMPLE).toLong(),
                        maxBytes = maxBatchBytes.toLong()
                    )
                )
            }
            val idx = count
            xs[idx] = x
            ys[idx] = y
            pressures[idx] = pressure
            tilts[idx] = tilt
            timestampsMs[idx] = timestampMs
            count = nextCount
            lastTimestampMs = timestampMs
            return Outcome.Success(nextCount)
        }

        @Synchronized
        fun overwriteSample(
            callerGeneration: Long,
            index: Int,
            x: Float,
            y: Float
        ): Outcome<Unit, SubmitError> {
            if (state != SlotState.WRITING || generation != callerGeneration) {
                return Outcome.Failure(
                    SubmitError.StaleHandle(
                        handleId = "input-slot-$slotIndex",
                        expectedGeneration = generation,
                        actualGeneration = callerGeneration
                    )
                )
            }
            if (index !in 0 until count) {
                return Outcome.Failure(SubmitError.InvalidPayload("Sample index $index out of range"))
            }
            xs[index] = x
            ys[index] = y
            return Outcome.Success(Unit)
        }

        @Synchronized
        fun freezeAndHandoff(
            callerGeneration: Long,
            session: SessionKey,
            pointerId: Int,
            sequenceNumber: Long,
            action: InputActionKind,
            maxBatchBytes: Int,
            onSlotReleased: () -> Unit
        ): Outcome<LeasedInputBatch, SubmitError> {
            if (state != SlotState.WRITING || generation != callerGeneration) {
                return Outcome.Failure(
                    SubmitError.StaleHandle(
                        handleId = "input-slot-$slotIndex",
                        expectedGeneration = generation,
                        actualGeneration = callerGeneration
                    )
                )
            }
            if (count <= 0) {
                // Release slot on failed handoff of empty batch
                state = SlotState.FREE
                generation += 1L
                return Outcome.Failure(SubmitError.InvalidPayload("Cannot handoff empty input batch (0 samples)"))
            }
            val batchOutcome = ImmutableInputBatch.copyFromArrays(
                session = session,
                pointerId = pointerId,
                sequenceNumber = sequenceNumber,
                action = action,
                count = count,
                xs = xs,
                ys = ys,
                pressures = pressures,
                tilts = tilts,
                timestampsMs = timestampsMs,
                maxBatchBytes = maxBatchBytes
            )
            return when (batchOutcome) {
                is Outcome.Failure -> {
                    state = SlotState.FREE
                    generation += 1L
                    batchOutcome
                }
                is Outcome.Success -> {
                    // Bump generation and transition to HANDED_OFF so the sender's PooledInputBuffer token is immediately invalidated
                    val handoffGen = generation
                    generation += 1L
                    state = SlotState.HANDED_OFF
                    Outcome.Success(
                        LeasedInputBatch(
                            batch = batchOutcome.value,
                            slotIndex = slotIndex,
                            generation = handoffGen,
                            onRelease = {
                                releaseHandedOff(handoffGen + 1L)
                                onSlotReleased()
                            }
                        )
                    )
                }
            }
        }

        @Synchronized
        fun cancelWriting(callerGeneration: Long): Boolean {
            if (state != SlotState.WRITING || generation != callerGeneration) {
                return false
            }
            generation += 1L
            count = 0
            state = SlotState.FREE
            return true
        }

        @Synchronized
        private fun releaseHandedOff(expectedGen: Long) {
            if (state == SlotState.HANDED_OFF && generation == expectedGen) {
                generation += 1L
                count = 0
                state = SlotState.FREE
            }
        }
    }

    private val slots = Array(poolSize) { idx -> PoolSlot(idx, maxSamplesPerBatch) }
    private val sequenceGen = AtomicLong(1L)

    fun nextSequenceNumber(): Long = sequenceGen.getAndIncrement()

    @Synchronized
    fun acquireBuffer(
        session: SessionKey,
        pointerId: Int,
        sequenceNumber: Long,
        action: InputActionKind
    ): Outcome<PooledInputBuffer, SubmitError> {
        for (slot in slots) {
            val gen = slot.acquire()
            if (gen != null) {
                return Outcome.Success(
                    PooledInputBuffer(
                        slotIndex = slot.slotIndex,
                        generation = gen,
                        session = session,
                        pointerId = pointerId,
                        sequenceNumber = sequenceNumber,
                        action = action,
                        slot = slot,
                        maxBatchBytes = maxBatchBytes
                    )
                )
            }
        }
        return Outcome.Failure(SubmitError.QueueOverflow(poolSize, poolSize))
    }

    fun handoff(buffer: PooledInputBuffer): Outcome<LeasedInputBatch, SubmitError> {
        val slot = slots[buffer.slotIndex]
        return slot.freezeAndHandoff(
            callerGeneration = buffer.generation,
            session = buffer.session,
            pointerId = buffer.pointerId,
            sequenceNumber = buffer.sequenceNumber,
            action = buffer.action,
            maxBatchBytes = maxBatchBytes,
            onSlotReleased = {}
        )
    }

    fun cancelBuffer(buffer: PooledInputBuffer): Boolean {
        val slot = slots[buffer.slotIndex]
        return slot.cancelWriting(buffer.generation)
    }

    /**
     * Copies real historical + current samples from an Android MotionEvent into a pooled buffer
     * and hands it off without retaining the MotionEvent reference after return.
     */
    fun extractFromMotionEvent(
        session: SessionKey,
        event: MotionEvent,
        pointerIndex: Int = event.actionIndex
    ): Outcome<LeasedInputBatch, SubmitError> {
        val masked = event.actionMasked
        val isCanceled = masked == MotionEvent.ACTION_CANCEL ||
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
        val actionKind = when {
            isCanceled -> InputActionKind.CANCEL
            masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN -> InputActionKind.DOWN
            masked == MotionEvent.ACTION_MOVE -> InputActionKind.MOVE
            masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_POINTER_UP -> InputActionKind.UP
            masked == MotionEvent.ACTION_HOVER_MOVE || masked == MotionEvent.ACTION_HOVER_ENTER || masked == MotionEvent.ACTION_HOVER_EXIT -> InputActionKind.HOVER
            else -> InputActionKind.MOVE
        }
        val pointerId = event.getPointerId(pointerIndex)
        val seq = nextSequenceNumber()
        val bufOutcome = acquireBuffer(session, pointerId, seq, actionKind)
        val buf = when (bufOutcome) {
            is Outcome.Failure -> return bufOutcome
            is Outcome.Success -> bufOutcome.value
        }

        val histSize = event.historySize
        for (h in 0 until histSize) {
            val appendRes = buf.appendSample(
                x = event.getHistoricalX(pointerIndex, h),
                y = event.getHistoricalY(pointerIndex, h),
                pressure = event.getHistoricalPressure(pointerIndex, h).coerceIn(0f, 1f),
                tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h),
                timestampMs = event.getHistoricalEventTime(h)
            )
            if (appendRes is Outcome.Failure) {
                cancelBuffer(buf)
                return appendRes
            }
        }
        val currentRes = buf.appendSample(
            x = event.getX(pointerIndex),
            y = event.getY(pointerIndex),
            pressure = event.getPressure(pointerIndex).coerceIn(0f, 1f),
            tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex),
            timestampMs = event.eventTime
        )
        if (currentRes is Outcome.Failure) {
            cancelBuffer(buf)
            return currentRes
        }
        return handoff(buf)
    }

    /**
     * Full F06.2 / F06.5 Android MotionEvent adapter extracting historical + current real samples,
     * tool type (`STYLUS`, `ERASER`, passive pen / `FINGER`, `MOUSE`), stylus primary & temporary eraser buttons,
     * `ACTION_CANCEL` vs per-pointer `FLAG_CANCELED`, and honest pressure/tilt axis capabilities.
     */
    fun extractInkBatchFromMotionEvent(
        session: SessionKey,
        event: MotionEvent,
        pointerIndex: Int = event.actionIndex,
        axisCapabilities: DeviceAxisCapabilities? = null,
        predictedSamples: List<PredictedSample> = emptyList()
    ): Outcome<LeasedInkInputBatch, SubmitError> {
        val masked = event.actionMasked
        val flags = event.flags
        val buttonState = event.buttonState
        val rawToolType = event.getToolType(pointerIndex)
        val toolType = mapAndroidToolType(rawToolType)
        val caps = axisCapabilities ?: DeviceAxisCapabilities.fromInputDevice(event.device, toolType)
        val isFlagCanceled = (flags and MotionEvent.FLAG_CANCELED) != 0
        val actionKind = mapAndroidAction(masked, isFlagCanceled)
        val primaryBtn = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val secondaryEraserBtn = (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

        val leasedOutcome = extractFromMotionEvent(session, event, pointerIndex)
        val leased = when (leasedOutcome) {
            is Outcome.Failure -> return leasedOutcome
            is Outcome.Success -> leasedOutcome.value
        }

        val inkBatch = InputBatch.fromImmutableBatch(
            batch = leased.batch,
            toolType = toolType,
            supportsPressure = caps.supportsPressure,
            supportsTilt = caps.supportsTilt,
            primaryButtonPressed = primaryBtn,
            secondaryEraserButtonPressed = secondaryEraserBtn,
            isFlagCanceled = isFlagCanceled,
            predictedSamples = predictedSamples
        )
        return Outcome.Success(LeasedInkInputBatch(leasedBatch = leased, inkBatch = inkBatch))
    }

    /**
     * Deterministic adapter entry point accepting raw Android MotionEvent constants (`actionMasked`, `flags`,
     * `buttonState`, `androidToolType`, historical + current primitive arrays, and `DeviceAxisCapabilities`).
     */
    fun extractInkBatchFromAndroidEventData(
        session: SessionKey,
        pointerId: Int,
        actionMasked: Int,
        flags: Int = 0,
        buttonState: Int = 0,
        androidToolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        axisCapabilities: DeviceAxisCapabilities? = null,
         sampleCount: Int,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray,
        tilts: FloatArray,
        timestampsMs: LongArray,
        predictedSamples: List<PredictedSample> = emptyList()
    ): Outcome<LeasedInkInputBatch, SubmitError> {
        val toolType = mapAndroidToolType(androidToolType)
        val caps = axisCapabilities ?: DeviceAxisCapabilities.fromInputDevice(null, toolType)
        val isFlagCanceled = (flags and MotionEvent.FLAG_CANCELED) != 0
        val actionKind = mapAndroidAction(actionMasked, isFlagCanceled)
        val primaryBtn = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val secondaryEraserBtn = (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

        val seq = nextSequenceNumber()
        val bufOutcome = acquireBuffer(session, pointerId, seq, actionKind)
        val buf = when (bufOutcome) {
            is Outcome.Failure -> return bufOutcome
            is Outcome.Success -> bufOutcome.value
        }

        for (i in 0 until sampleCount) {
            val effPressure = if (caps.supportsPressure) pressures[i] else 0.5f
            val effTilt = if (caps.supportsTilt) tilts[i] else 0.0f
            val appendRes = buf.appendSample(
                x = xs[i],
                y = ys[i],
                pressure = effPressure,
                tilt = effTilt,
                timestampMs = timestampsMs[i]
            )
            if (appendRes is Outcome.Failure) {
                cancelBuffer(buf)
                return appendRes
            }
        }

        val leasedOutcome = handoff(buf)
        val leased = when (leasedOutcome) {
            is Outcome.Failure -> return leasedOutcome
            is Outcome.Success -> leasedOutcome.value
        }

        val inkBatch = InputBatch.fromImmutableBatch(
            batch = leased.batch,
            toolType = toolType,
            supportsPressure = caps.supportsPressure,
            supportsTilt = caps.supportsTilt,
            primaryButtonPressed = primaryBtn,
            secondaryEraserButtonPressed = secondaryEraserBtn,
            isFlagCanceled = isFlagCanceled,
            predictedSamples = predictedSamples
        )
        return Outcome.Success(LeasedInkInputBatch(leasedBatch = leased, inkBatch = inkBatch))
    }

    companion object {
        fun mapAndroidToolType(androidToolType: Int): InputToolType {
            return when (androidToolType) {
                MotionEvent.TOOL_TYPE_STYLUS -> InputToolType.STYLUS
                MotionEvent.TOOL_TYPE_ERASER -> InputToolType.ERASER
                MotionEvent.TOOL_TYPE_FINGER -> InputToolType.FINGER
                MotionEvent.TOOL_TYPE_MOUSE -> InputToolType.MOUSE
                else -> InputToolType.UNKNOWN
            }
        }

        fun mapAndroidAction(actionMasked: Int, isFlagCanceled: Boolean): InputActionKind {
            return when {
                actionMasked == MotionEvent.ACTION_CANCEL -> InputActionKind.CANCEL
                isFlagCanceled && (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_POINTER_UP) ->
                    InputActionKind.UP
                isFlagCanceled -> InputActionKind.CANCEL
                actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN ->
                    InputActionKind.DOWN
                actionMasked == MotionEvent.ACTION_MOVE -> InputActionKind.MOVE
                actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_POINTER_UP ->
                    InputActionKind.UP
                actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                    actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                    actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
                    actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                    actionMasked == MotionEvent.ACTION_BUTTON_RELEASE -> InputActionKind.HOVER
                else -> InputActionKind.MOVE
            }
        }
    }
}
