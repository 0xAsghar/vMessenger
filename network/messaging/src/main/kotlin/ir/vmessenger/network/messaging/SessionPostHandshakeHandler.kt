package ir.vmessenger.network.messaging

/**
 * Optional hooks invoked after a secure session is established (Phases 4 & 8).
 */
fun interface SessionPostHandshakeHandler {
    suspend fun onEstablished(session: ActiveSecureSession, self: PeerIdentity, peer: PeerIdentity)
}
