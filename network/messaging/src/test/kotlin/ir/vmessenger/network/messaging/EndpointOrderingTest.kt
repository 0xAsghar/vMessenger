package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportIds
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointOrderingTest {
    @Test
    fun internetEndpointsRankBeforeRelay() {
        P2PConfig.resetToDefaults()
        val endpoints = listOf(
            Endpoint(TransportIds.RELAY, "wss://node.example.org/relay"),
            Endpoint(TransportIds.INTERNET, "203.0.113.1:48555"),
        )
        val ordered = EndpointOrder.order(endpoints)
        assertEquals(TransportIds.INTERNET, ordered.first().transport)
        assertEquals(TransportIds.RELAY, ordered.last().transport)
    }

    @Test
    fun relaysKeepTheOrderTheyWereGivenIn() {
        P2PConfig.resetToDefaults()
        val endpoints = listOf(
            Endpoint(TransportIds.RELAY, "wss://first.example/relay"),
            Endpoint(TransportIds.RELAY, "wss://second.example/relay"),
            Endpoint(TransportIds.INTERNET, "203.0.113.1:48555"),
        )
        val ordered = EndpointOrder.order(endpoints)
        assertEquals(
            listOf("203.0.113.1:48555", "wss://first.example/relay", "wss://second.example/relay"),
            ordered.map { it.address },
        )
    }
}
