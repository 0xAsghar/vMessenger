package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportIds

/**
 * Orders peer endpoints according to the Phase 9 preference stack while keeping
 * the default relay available as a last resort when [P2PConfig.reduceDefaultRelayEnabled].
 */
object EndpointOrder {
    fun order(endpoints: List<Endpoint>): List<Endpoint> =
        endpoints.sortedWith(
            compareBy(
                { transportRank(it) },
                { relayDemotionRank(it) },
            ),
        )

    private fun transportRank(endpoint: Endpoint): Int = when (endpoint.transport) {
        TransportIds.INTERNET -> 0
        TransportIds.UDP -> if (P2PConfig.natTraversalEnabled) 1 else 99
        TransportIds.RELAY -> 2
        else -> 3
    }

    private fun relayDemotionRank(endpoint: Endpoint): Int {
        if (endpoint.transport != TransportIds.RELAY) return 0
        if (!P2PConfig.reduceDefaultRelayEnabled) return 0
        return if (endpoint.address == NetworkConfig.DEFAULT_RELAY_URL) 1 else 0
    }
}
