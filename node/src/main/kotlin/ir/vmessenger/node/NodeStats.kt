package ir.vmessenger.node

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free counters exposed on `/healthz?verbose=1`.
 *
 * Gauges ([listeners], [pendingDialers], [activeCircuits], [dhtRecords]) track
 * current occupancy; the `rejected*` counters only ever grow.
 */
class NodeStats(
    val startedAtMs: Long = System.currentTimeMillis(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val listeners = AtomicInteger()
    val pendingDialers = AtomicInteger()
    val activeCircuits = AtomicInteger()
    val dhtRecords = AtomicInteger()

    val rejectedStaleProof = AtomicLong()
    val rejectedReplayedProof = AtomicLong()
    val rejectedRateLimited = AtomicLong()
    val rejectedRelayFull = AtomicLong()
    val rejectedListenerBusy = AtomicLong()
    val rejectedInvalidHello = AtomicLong()

    fun uptimeMs(): Long = (clock() - startedAtMs).coerceAtLeast(0L)

    /** Single-line JSON document; every value is a number so no escaping is needed. */
    fun toJson(): String = buildString {
        append('{')
        append("\"startedAtMs\":").append(startedAtMs)
        append(",\"uptimeMs\":").append(uptimeMs())
        append(",\"listeners\":").append(listeners.get())
        append(",\"pendingDialers\":").append(pendingDialers.get())
        append(",\"activeCircuits\":").append(activeCircuits.get())
        append(",\"dhtRecords\":").append(dhtRecords.get())
        append(",\"rejected\":{")
        append("\"staleProof\":").append(rejectedStaleProof.get())
        append(",\"replayedProof\":").append(rejectedReplayedProof.get())
        append(",\"rateLimited\":").append(rejectedRateLimited.get())
        append(",\"relayFull\":").append(rejectedRelayFull.get())
        append(",\"listenerBusy\":").append(rejectedListenerBusy.get())
        append(",\"invalidHello\":").append(rejectedInvalidHello.get())
        append("}}")
    }
}
