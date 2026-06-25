package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointOrderingTest {
    @Test
    fun internetEndpointsRankBeforeRelay() {
        val endpoints = listOf(
            Endpoint(TransportIds.RELAY, "wss://relay.vmessenger.ir/relay"),
            Endpoint(TransportIds.INTERNET, "203.0.113.1:48555"),
        )
        val ordered = endpoints.sortedBy {
            when (it.transport) {
                TransportIds.INTERNET -> 0
                TransportIds.RELAY -> 1
                else -> 2
            }
        }
        assertEquals(TransportIds.INTERNET, ordered.first().transport)
        assertEquals(TransportIds.RELAY, ordered.last().transport)
    }
}
