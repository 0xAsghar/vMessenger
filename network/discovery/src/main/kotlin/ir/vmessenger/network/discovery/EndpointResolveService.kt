package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.P2PConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves peer endpoints with a cache-first policy.
 * Only endpoints the peer actually published are returned (plus the default
 * relay as a last resort); direct TCP endpoints are no longer mirrored as UDP
 * candidates — UDP cannot carry a handshake and NAT traversal is off in 1.0.
 */
@Singleton
class EndpointResolveService @Inject constructor(
    private val peerEndpointCache: PeerEndpointCache,
    private val discoveryManager: DiscoveryManager,
) {
    data class Resolved(
        val endpoints: List<Endpoint>,
        val fromPeerCache: Boolean,
        /**
         * True when every discovery provider failed (DHT/bootstrap unreachable) and
         * [endpoints] is only the relay fallback: a dial failure is then a network
         * problem, not a peer without a record.
         */
        val discoveryFailed: Boolean = false,
    )

    /** Drops any cached endpoints for a peer so the next resolve re-hits the DHT. */
    suspend fun invalidate(identityHash: ByteArray) {
        peerEndpointCache.evict(identityHash)
    }

    /**
     * Never fails outright: a relay circuit needs only the identity hash, so even
     * when discovery itself errors (not bootstrapped, DHT down) the default relay
     * is returned with [Resolved.discoveryFailed] set, and delivery via the relay
     * does not depend on the DHT being reachable.
     */
    suspend fun resolve(identityHash: ByteArray): AppResult<Resolved> {
        if (P2PConfig.peerCacheEnabled) {
            val cached = peerEndpointCache.lookup(identityHash)
            if (cached != null && cached.isNotEmpty()) {
                return AppResult.Success(Resolved(endpoints = withRelayFallback(cached), fromPeerCache = true))
            }
        }
        return when (val result = discoveryManager.resolve(identityHash)) {
            is AppResult.Success ->
                AppResult.Success(Resolved(endpoints = withRelayFallback(result.data), fromPeerCache = false))
            is AppResult.Error -> {
                AppLogger.warn("Discovery", "resolve failed: network (${result.error.message}); trying relay")
                AppResult.Success(
                    Resolved(
                        endpoints = NetworkConfig.relayFallbackEndpoints(),
                        fromPeerCache = false,
                        discoveryFailed = true,
                    ),
                )
            }
        }
    }

    /**
     * Relay circuits only need the peer identity hash, so when DHT/cache lookup
     * returns nothing we still synthesize the default relay as a last-resort path.
     */
    private fun withRelayFallback(endpoints: List<Endpoint>): List<Endpoint> {
        if (endpoints.isNotEmpty()) return endpoints
        AppLogger.info("Discovery", "no peer endpoints; falling back to default relay")
        return NetworkConfig.relayFallbackEndpoints()
    }
}
