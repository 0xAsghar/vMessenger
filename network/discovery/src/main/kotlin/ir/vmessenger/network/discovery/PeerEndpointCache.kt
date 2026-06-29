package ir.vmessenger.network.discovery

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.proto.dht.v1.EndpointRecord

/**
 * Local cache of previously resolved, signature-verified peer endpoint records
 * (docs/P2P-Phases.md Phase 3). Lets the app reach known peers even when public
 * bootstrap infrastructure is unreachable.
 *
 * Security: implementations must verify record signatures, expire stale entries,
 * and never let a cached record override a newer signed record (higher sequence).
 */
interface PeerEndpointCache {
    /** Returns cached, verified, non-expired endpoints for [identityHash], or null. */
    suspend fun lookup(identityHash: ByteArray): List<Endpoint>?

    /** Stores a verified record if it is newer than any cached entry. */
    suspend fun store(record: EndpointRecord)
}
