package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.network.messaging.IncomingEnvelope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-relay (relay-capable peer) circuit handling is not part of 1.0: every
 * secure frame is bound to its own session's ratchet, so opaque ciphertext
 * sealed under another session can never be forwarded. Circuit requests are
 * acknowledged as *not handled* so the sender falls back to the central relay.
 */
@Singleton
class PeerRelayForwarder @Inject constructor() {
    fun handleOpen(incoming: IncomingEnvelope): Boolean {
        AppLogger.info("PeerRelay", "relay open ignored contact=${incoming.contactId} (user relay disabled in 1.0)")
        return false
    }

    fun handleData(incoming: IncomingEnvelope): Boolean {
        AppLogger.debug("PeerRelay", "relay data ignored contact=${incoming.contactId} (user relay disabled in 1.0)")
        return false
    }
}
