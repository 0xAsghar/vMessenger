package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.EndpointSource
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.network.transport.TransportSelector
import kotlinx.coroutines.withTimeout

/**
 * Connect + handshake for outbound sessions. Pure "open a session" logic with
 * per-endpoint fallback and path bookkeeping; the caller owns the session's
 * lifetime (slot, read loop, close).
 */
internal class OutboundDialer(
    private val transportSelector: TransportSelector,
    private val secureChannelFactory: SecureChannelFactory,
) {
    /**
     * Tries [endpoints] in order and returns the first established session, or
     * the last failure. A protocol-version or pinned-key failure is the same on
     * every endpoint, so those stop the fallback immediately.
     */
    suspend fun dialAny(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoints: List<Endpoint>,
        fromPeerCache: Boolean,
    ): Result<ActiveSecureSession> {
        val source = if (fromPeerCache) EndpointSource.CACHE else EndpointSource.DHT
        var lastFailure: Throwable? = null
        for (endpoint in endpoints) {
            AppLogger.info(TAG, "try ${endpoint.transport.value}:${endpoint.address}")
            val attempt = dialEndpoint(self, peer, endpoint)
            val cause = attempt.exceptionOrNull()
            if (cause == null) {
                recordSuccess(endpoint, source, fromPeerCache)
                return attempt
            }
            AppLogger.warn(TAG, "failed ${endpoint.transport.value}: ${cause.message}")
            NetworkPathTracker.recordAttempt(
                endpoint = endpoint.describe(),
                source = source,
                success = false,
                failureReason = cause.message,
            )
            lastFailure = cause
            if (cause is ProtocolVersionException || cause is PeerKeyChangedException) break
        }
        AppLogger.error(TAG, "all transports failed for contact=$contactId")
        return Result.failure(lastFailure ?: IllegalStateException(MessagingService.SEND_FAILED_MESSAGE))
    }

    /**
     * Connects to one [endpoint] and runs the initiator handshake, bounded by
     * [DIAL_TIMEOUT_MS] so a hung transport can never pin the contact's slot.
     * The connection is closed on any failure.
     */
    suspend fun dialEndpoint(self: PeerIdentity, peer: PeerIdentity, endpoint: Endpoint): Result<ActiveSecureSession> =
        runCatching {
            withTimeout(DIAL_TIMEOUT_MS) {
                val connection = transportSelector.connect(
                    endpoint,
                    relayTargetId = if (endpoint.transport == TransportIds.RELAY) peer.identityHash else null,
                ).getOrThrow()
                runCatching { secureChannelFactory.initiate(connection, self, peer).getOrThrow() }
                    .onFailure { connection.close() }
                    .getOrThrow() as ActiveSecureSession
            }
        }

    private fun recordSuccess(endpoint: Endpoint, source: EndpointSource, fromPeerCache: Boolean) {
        AppLogger.info(TAG, "session established via ${endpoint.transport.value}")
        NetworkPathTracker.recordAttempt(endpoint = endpoint.describe(), source = source, success = true)
        val path = if (fromPeerCache) NetworkPath.CACHED_PEER else endpoint.toNetworkPath()
        NetworkPathTracker.record(path = path, detail = endpoint.describe())
    }

    private fun Endpoint.describe(): String = "${transport.value}:$address"

    private companion object {
        const val TAG = "Messaging"

        // Bounds connect + handshake. Comfortably above the relay dial timeout
        // (15s) and handshake read timeout (15s) so a legitimately slow path
        // still completes, but a wedged one is always released.
        const val DIAL_TIMEOUT_MS = 40_000L
    }
}

/**
 * Classifies the transport path a successful send used so debug tooling can show
 * whether traffic took a direct, relay, or user/community relay route. Direct
 * INTERNET endpoints are reported as [NetworkPath.DIRECT]; relay endpoints are
 * split into the central default relay versus a user/community relay.
 */
internal fun Endpoint.toNetworkPath(): NetworkPath = when (transport) {
    TransportIds.INTERNET -> NetworkPath.DIRECT
    TransportIds.UDP -> NetworkPath.UDP_ATTEMPT
    TransportIds.RELAY ->
        if (address == NetworkConfig.DEFAULT_RELAY_URL) NetworkPath.DEFAULT_RELAY else NetworkPath.USER_RELAY
    else -> NetworkPath.UNKNOWN
}
