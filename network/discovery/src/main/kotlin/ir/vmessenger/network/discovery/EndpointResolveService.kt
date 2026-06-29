package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves peer endpoints with a cache-first policy (docs/P2P-Phases.md Phase 3).
 */
@Singleton
class EndpointResolveService @Inject constructor(
    private val peerEndpointCache: PeerEndpointCache,
    private val discoveryManager: DiscoveryManager,
) {
    data class Resolved(
        val endpoints: List<Endpoint>,
        val fromPeerCache: Boolean,
    )

    suspend fun resolve(identityHash: ByteArray): AppResult<Resolved> {
        if (P2PConfig.peerCacheEnabled) {
            val cached = peerEndpointCache.lookup(identityHash)
            if (cached != null && cached.isNotEmpty()) {
                return AppResult.Success(
                    Resolved(endpoints = expandTransports(cached), fromPeerCache = true),
                )
            }
        }
        return when (val result = discoveryManager.resolve(identityHash)) {
            is AppResult.Success ->
                AppResult.Success(
                    Resolved(endpoints = expandTransports(result.data), fromPeerCache = false),
                )
            is AppResult.Error -> result
        }
    }

    /** Phase 7: mirror direct TCP endpoints as UDP candidates when NAT traversal is enabled. */
    private fun expandTransports(endpoints: List<Endpoint>): List<Endpoint> {
        if (!P2PConfig.natTraversalEnabled) return endpoints
        val udpMirrors = endpoints
            .filter { it.transport == TransportIds.INTERNET && it.address.contains(':') }
            .map { Endpoint(transport = TransportIds.UDP, address = it.address) }
        return endpoints + udpMirrors
    }
}
