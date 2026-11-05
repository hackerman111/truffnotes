package inkspace.runtime

import inkspace.core.error.SubmitError
import inkspace.core.model.SessionKey
import inkspace.core.outcome.Outcome
import inkspace.core.ports.DurableAck
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.EventBatch
import inkspace.core.runtime.ImmutableInputBatch
import inkspace.core.runtime.MemoryCategory
import inkspace.core.runtime.RuntimeConfig

internal class PersistReservation(
    val estimatedBytes: Long,
    val ackSlotBytes: Long,
    val queueLease: MemoryLease,
    private val onRelease: (PersistReservation) -> Unit
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            queueLease.close()
            onRelease(this)
        }
    }
}

internal sealed interface ScheduledInputItem {
    val session: SessionKey
    val pointerId: Int
    val sequenceNumber: Long

    data class BatchItem(
        val batch: ImmutableInputBatch,
        val lease: MemoryLease
    ) : ScheduledInputItem {
        override val session: SessionKey get() = batch.session
        override val pointerId: Int get() = batch.pointerId
        override val sequenceNumber: Long get() = batch.sequenceNumber
    }

    data class TerminalControlItem(
        val command: AppCommand.SubmitControl
    ) : ScheduledInputItem {
        override val session: SessionKey get() = command.session
        override val pointerId: Int get() = command.pointerId
        override val sequenceNumber: Long get() = command.sequenceNumber
    }
}

/**
 * Implements all bounded runtime queue protocols from Architecture §7:
 * - Samples Main -> Engine (4 MiB, batch <= 64 KiB, lossless MOVE coalescing, explicit cancel on overflow)
 * - Terminal input/control (64 reserved slots, sequence-ordered, never drop newest)
 * - Edit/persist FIFO (256 envelopes, <= 8 MiB) + DurableAck reservation (256 slots / 256 KiB)
 * - Events service -> Main (256 envelopes, <= 2 MiB, metadata coalescing by version) + Error channel (64 slots / 128 KiB)
 */
internal class RuntimeQueues(
    private val config: RuntimeConfig,
    private val governor: MemoryGovernor
) {
    companion object {
        const val ACK_SLOT_BYTES: Long = 1024L
        const val EVENT_ENVELOPE_BYTES: Long = 512L
        const val ERROR_ENVELOPE_BYTES: Long = 512L
    }

    private val inputSamples = ArrayDeque<ScheduledInputItem.BatchItem>()
    private var inputSamplesBytes: Long = 0L

    private val terminalControls = ArrayList<ScheduledInputItem.TerminalControlItem>()

    private var pendingPersistEnvelopes: Int = 0
    private var pendingPersistBytes: Long = 0L
    private var reservedAckSlots: Int = 0
    private var reservedAckBytes: Long = 0L
    private val arrivedDurableAcks = ArrayDeque<DurableAck>()

    private val mainEvents = ArrayList<AppEvent>()
    private var mainEventsBytes: Long = 0L
    private var coalescedMetadataCount: Int = 0

    private val errorEvents = ArrayList<AppEvent.ErrorEvent>()
    private var errorEventsBytes: Long = 0L

    @Synchronized
    fun enqueueInputBatch(batch: ImmutableInputBatch): Outcome<Unit, SubmitError> {
        if (batch.byteSize > config.maxInputBatchBytes) {
            return Outcome.Failure(
                SubmitError.ByteBudgetExceeded(
                    requestedBytes = batch.byteSize.toLong(),
                    currentBytes = inputSamplesBytes,
                    maxBytes = config.maxInputBatchBytes.toLong()
                )
            )
        }

        // Try lossless coalescing if the tail item is a MOVE for the same session & pointer
        val tail = inputSamples.lastOrNull()
        if (tail != null) {
            val merged = tail.batch.coalesceMoveWith(batch, config.maxInputBatchBytes)
            if (merged != null) {
                val deltaBytes = (merged.byteSize - tail.batch.byteSize).toLong().coerceAtLeast(0L)
                if (inputSamplesBytes + deltaBytes <= config.inputQueueMaxBytes &&
                    tail.lease.adjustActualBytes(tail.lease.reservedBytes + deltaBytes)
                ) {
                    inputSamples.removeLast()
                    inputSamples.addLast(ScheduledInputItem.BatchItem(merged, tail.lease))
                    inputSamplesBytes += deltaBytes
                    return Outcome.Success(Unit)
                }
            }
        }

        val reqBytes = batch.byteSize.toLong()
        if (inputSamplesBytes + reqBytes > config.inputQueueMaxBytes) {
            emitInputOverflowCancel(batch)
            return Outcome.Failure(
                SubmitError.ByteBudgetExceeded(
                    requestedBytes = reqBytes,
                    currentBytes = inputSamplesBytes,
                    maxBytes = config.inputQueueMaxBytes
                )
            )
        }

        val leaseRes = governor.tryReserve(
            category = MemoryCategory.INPUT_IPC_PERSISTENCE_QUEUES,
            bytes = reqBytes,
            owner = "input-batch:${batch.session.document.value}:seq-${batch.sequenceNumber}"
        )
        val lease = when (leaseRes) {
            is Outcome.Failure -> {
                emitInputOverflowCancel(batch)
                return Outcome.Failure(
                    SubmitError.ByteBudgetExceeded(
                        requestedBytes = reqBytes,
                        currentBytes = governor.usedBytes(MemoryCategory.INPUT_IPC_PERSISTENCE_QUEUES),
                        maxBytes = governor.capBytes(MemoryCategory.INPUT_IPC_PERSISTENCE_QUEUES)
                    )
                )
            }
            is Outcome.Success -> leaseRes.value
        }

        inputSamples.addLast(ScheduledInputItem.BatchItem(batch, lease))
        inputSamplesBytes += reqBytes
        return Outcome.Success(Unit)
    }

    @Synchronized
    private fun emitInputOverflowCancel(batch: ImmutableInputBatch) {
        emitMainEvent(
            AppEvent.InputDraftCanceled(
                session = batch.session,
                pointerId = batch.pointerId,
                sequenceNumber = batch.sequenceNumber,
                reason = "Input sample queue byte cap (${config.inputQueueMaxBytes} B) exceeded"
            )
        )
        emitMainEvent(
            AppEvent.ErrorEvent(
                session = batch.session,
                code = "INPUT_OVERFLOW",
                message = "Input sample batch rejected due to queue byte limit; active draft canceled"
            )
        )
    }

    @Synchronized
    fun enqueueTerminalControl(cmd: AppCommand.SubmitControl): Outcome<Unit, SubmitError> {
        if (terminalControls.size >= config.terminalControlReservedSlots) {
            return Outcome.Failure(
                SubmitError.QueueOverflow(terminalControls.size, config.terminalControlReservedSlots)
            )
        }
        terminalControls.add(ScheduledInputItem.TerminalControlItem(cmd))
        terminalControls.sortBy { it.sequenceNumber }
        return Outcome.Success(Unit)
    }

    @Synchronized
    fun hasPendingInputOrControl(): Boolean {
        return inputSamples.isNotEmpty() || terminalControls.isNotEmpty()
    }

    /**
     * Dequeues the next input batch or terminal control event in sequence order,
     * ensuring terminal UP/CANCEL never overtakes an earlier sample batch for the same pointer.
     */
    @Synchronized
    fun pollNextInputOrControl(): ScheduledInputItem? {
        val headSample = inputSamples.firstOrNull()
        val headTerminal = terminalControls.firstOrNull()

        if (headSample == null && headTerminal == null) return null
        if (headSample != null && headTerminal == null) {
            val removed = inputSamples.removeFirst()
            inputSamplesBytes = (inputSamplesBytes - removed.batch.byteSize).coerceAtLeast(0L)
            return removed
        }
        if (headSample == null && headTerminal != null) {
            return terminalControls.removeAt(0)
        }
        if (headSample != null && headTerminal != null) {
            // Do not allow terminal event to overtake an earlier sample batch
            val sampleFirst = headSample.sequenceNumber <= headTerminal.sequenceNumber
            return if (sampleFirst) {
                val removed = inputSamples.removeFirst()
                inputSamplesBytes = (inputSamplesBytes - removed.batch.byteSize).coerceAtLeast(0L)
                removed
            } else {
                terminalControls.removeAt(0)
            }
        }
        return null
    }

    @Synchronized
    fun tryReservePersistAndAck(
        session: SessionKey,
        estimatedBytes: Long
    ): Outcome<PersistReservation, SubmitError> {
        val cleanBytes = estimatedBytes.coerceAtLeast(64L)
        if (pendingPersistEnvelopes >= config.persistQueueMaxEnvelopes ||
            pendingPersistBytes + cleanBytes > config.persistQueueMaxBytes ||
            reservedAckSlots >= config.durableAckMaxSlots ||
            reservedAckBytes + ACK_SLOT_BYTES > config.durableAckMaxBytes
        ) {
            emitMainEvent(
                AppEvent.ErrorEvent(
                    session = session,
                    code = "PERSIST_OVERFLOW",
                    message = "Pending persistence queue reached capacity ($pendingPersistEnvelopes/${config.persistQueueMaxEnvelopes})"
                )
            )
            return Outcome.Failure(
                SubmitError.PendingPersistenceOverflow(
                    pendingCount = pendingPersistEnvelopes,
                    pendingBytes = pendingPersistBytes
                )
            )
        }

        val totalReserve = cleanBytes + ACK_SLOT_BYTES
        val leaseOutcome = governor.tryReserve(
            category = MemoryCategory.INPUT_IPC_PERSISTENCE_QUEUES,
            bytes = totalReserve,
            owner = "persist:${session.document.value}"
        )
        val lease = when (leaseOutcome) {
            is Outcome.Failure -> {
                emitMainEvent(
                    AppEvent.ErrorEvent(
                        session = session,
                        code = "QUEUE_MEMORY_CAP",
                        message = "Global 16 MiB queue memory cap exceeded"
                    )
                )
                return Outcome.Failure(
                    SubmitError.PendingPersistenceOverflow(
                        pendingCount = pendingPersistEnvelopes,
                        pendingBytes = pendingPersistBytes
                    )
                )
            }
            is Outcome.Success -> leaseOutcome.value
        }

        pendingPersistEnvelopes += 1
        pendingPersistBytes += cleanBytes
        reservedAckSlots += 1
        reservedAckBytes += ACK_SLOT_BYTES

        return Outcome.Success(
            PersistReservation(
                estimatedBytes = cleanBytes,
                ackSlotBytes = ACK_SLOT_BYTES,
                queueLease = lease,
                onRelease = { released ->
                    synchronized(this) {
                        pendingPersistEnvelopes = (pendingPersistEnvelopes - 1).coerceAtLeast(0)
                        pendingPersistBytes = (pendingPersistBytes - released.estimatedBytes).coerceAtLeast(0L)
                        reservedAckSlots = (reservedAckSlots - 1).coerceAtLeast(0)
                        reservedAckBytes = (reservedAckBytes - released.ackSlotBytes).coerceAtLeast(0L)
                    }
                }
            )
        )
    }

    @Synchronized
    fun enqueueDurableAck(ack: DurableAck) {
        arrivedDurableAcks.addLast(ack)
    }

    @Synchronized
    fun pollNextDurableAck(): DurableAck? {
        return if (arrivedDurableAcks.isEmpty()) null else arrivedDurableAcks.removeFirst()
    }

    @Synchronized
    fun hasPendingDurableAcks(): Boolean = arrivedDurableAcks.isNotEmpty()

    @Synchronized
    fun emitMainEvent(event: AppEvent) {
        if (event is AppEvent.ErrorEvent) {
            if (errorEvents.size < config.errorEventsMaxSlots &&
                errorEventsBytes + ERROR_ENVELOPE_BYTES <= config.errorEventsMaxBytes
            ) {
                errorEvents.add(event)
                errorEventsBytes += ERROR_ENVELOPE_BYTES
            } else if (errorEvents.isNotEmpty()) {
                // Coalesce latest error slot if error buffer is saturated
                errorEvents[errorEvents.lastIndex] = event
            }
            return
        }

        // Metadata coalescing by session & event kind
        when (event) {
            is AppEvent.SnapshotPublished -> {
                val idx = mainEvents.indexOfLast {
                    it is AppEvent.SnapshotPublished && it.session == event.session
                }
                if (idx >= 0) {
                    mainEvents[idx] = event
                    coalescedMetadataCount += 1
                    return
                }
            }
            is AppEvent.SaveStatusChanged -> {
                val idx = mainEvents.indexOfLast {
                    it is AppEvent.SaveStatusChanged && it.saveStatus.session == event.saveStatus.session
                }
                if (idx >= 0) {
                    mainEvents[idx] = event
                    coalescedMetadataCount += 1
                    return
                }
            }
            is AppEvent.ViewportChanged -> {
                val idx = mainEvents.indexOfLast {
                    it is AppEvent.ViewportChanged && it.viewport.session == event.viewport.session
                }
                if (idx >= 0) {
                    mainEvents[idx] = event
                    coalescedMetadataCount += 1
                    return
                }
            }
            else -> {}
        }

        if (mainEvents.size >= config.mainEventsMaxEnvelopes ||
            mainEventsBytes + EVENT_ENVELOPE_BYTES > config.mainEventsMaxBytes
        ) {
            // Evict oldest coalescible metadata event if present
            val evictIdx = mainEvents.indexOfFirst {
                it is AppEvent.SnapshotPublished || it is AppEvent.SaveStatusChanged || it is AppEvent.ViewportChanged
            }
            if (evictIdx >= 0) {
                mainEvents.removeAt(evictIdx)
                mainEventsBytes = (mainEventsBytes - EVENT_ENVELOPE_BYTES).coerceAtLeast(0L)
                coalescedMetadataCount += 1
            } else if (mainEvents.isNotEmpty()) {
                mainEvents.removeAt(0)
                mainEventsBytes = (mainEventsBytes - EVENT_ENVELOPE_BYTES).coerceAtLeast(0L)
            }
        }
        mainEvents.add(event)
        mainEventsBytes += EVENT_ENVELOPE_BYTES
    }

    @Synchronized
    fun pollEvents(max: Int): EventBatch {
        val limit = max.coerceAtLeast(1)
        val out = ArrayList<AppEvent>(minOf(limit, mainEvents.size))
        while (mainEvents.isNotEmpty() && out.size < limit) {
            val ev = mainEvents.removeAt(0)
            mainEventsBytes = (mainEventsBytes - EVENT_ENVELOPE_BYTES).coerceAtLeast(0L)
            out.add(ev)
        }
        val errorsSnapshot = errorEvents.toList()
        val coalesced = coalescedMetadataCount
        coalescedMetadataCount = 0
        return EventBatch(
            events = out,
            errorEvents = errorsSnapshot,
            coalescedMetadataCount = coalesced
        )
    }

    @Synchronized
    fun acknowledgeError(error: AppEvent.ErrorEvent) {
        if (errorEvents.remove(error)) {
            errorEventsBytes = (errorEventsBytes - ERROR_ENVELOPE_BYTES).coerceAtLeast(0L)
        }
    }
}
