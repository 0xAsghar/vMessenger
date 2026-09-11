package ir.vmessenger.node

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeStatsTest {

    @Test
    fun `json reflects counters and uptime`() {
        var now = 5_000L
        val stats = NodeStats(startedAtMs = 1_000L, clock = { now })
        stats.listeners.set(2)
        stats.pendingDialers.set(1)
        stats.activeCircuits.set(3)
        stats.dhtRecords.set(4)
        stats.rejectedStaleProof.incrementAndGet()
        stats.rejectedReplayedProof.addAndGet(2)
        stats.rejectedRateLimited.addAndGet(3)
        stats.rejectedRelayFull.addAndGet(4)
        stats.rejectedListenerBusy.addAndGet(5)
        stats.rejectedInvalidHello.addAndGet(6)
        now = 11_000L

        assertEquals(
            "{\"startedAtMs\":1000,\"uptimeMs\":10000,\"listeners\":2,\"pendingDialers\":1,\"activeCircuits\":3," +
                "\"dhtRecords\":4,\"rejected\":{\"staleProof\":1,\"replayedProof\":2,\"rateLimited\":3," +
                "\"relayFull\":4,\"listenerBusy\":5,\"invalidHello\":6}}",
            stats.toJson(),
        )
    }

    @Test
    fun `uptime never goes negative`() {
        val stats = NodeStats(startedAtMs = 10_000L, clock = { 0L })
        assertEquals(0L, stats.uptimeMs())
    }
}
