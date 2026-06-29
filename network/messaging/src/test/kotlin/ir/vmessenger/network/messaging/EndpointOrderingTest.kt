package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportIds
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointOrderingTest {
    @Test
    fun internetEndpointsRankBeforeRelay() {
        P2PConfig.resetToDefaults()
        val endpoints = listOf(
            Endpoint(TransportIds.RELAY, NetworkConfig.DEFAULT_RELAY_URL),
            Endpoint(TransportIds.INTERNET, "203.0.113.1:48555"),
        )
        val ordered = EndpointOrder.order(endpoints)
        assertEquals(TransportIds.INTERNET, ordered.first().transport)
        assertEquals(TransportIds.RELAY, ordered.last().transport)
    }

    @Test
    fun defaultRelayDemotedWhenPhase9Enabled() {
        P2PConfig.resetToDefaults()
        P2PConfig.reduceDefaultRelayEnabled = true
        val endpoints = listOf(
            Endpoint(TransportIds.RELAY, NetworkConfig.DEFAULT_RELAY_URL),
            Endpoint(TransportIds.RELAY, "wss://community.example/relay"),
        )
        val ordered = EndpointOrder.order(endpoints)
        assertEquals("wss://community.example/relay", ordered.first().address)
        assertEquals(NetworkConfig.DEFAULT_RELAY_URL, ordered.last().address)
    }
}
