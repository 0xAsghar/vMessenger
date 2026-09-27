package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportIds

/**
 * Orders peer endpoints by transport: a direct address first, then UDP (only while UDP attempts are
 * on), then relays, then anything unknown. The sort is stable, so relays keep the order they were
 * given in. There is no default relay to demote since 2.2.2 removed the built-in node.
 */
object EndpointOrder {
    fun order(endpoints: List<Endpoint>): List<Endpoint> = endpoints.sortedBy { transportRank(it) }

    private fun transportRank(endpoint: Endpoint): Int = when (endpoint.transport) {
        TransportIds.INTERNET -> 0
        TransportIds.UDP -> if (P2PConfig.natTraversalEnabled) 1 else 99
        TransportIds.RELAY -> 2
        else -> 3
    }
}
