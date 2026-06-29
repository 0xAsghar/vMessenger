package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.P2PConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 6: gate for peer-operated relay mode. When enabled, the device advertises
 * relay-peer capability in the handshake and accepts encrypted frame forwarding
 * subject to circuit limits configured in [P2PConfig].
 */
@Singleton
class PeerRelayCoordinator @Inject constructor() {
    fun isActive(): Boolean = P2PConfig.relayPeerModeEnabled

    fun logStatus() {
        if (isActive()) {
            AppLogger.info("PeerRelay", "relay-peer mode enabled (encrypted forward only)")
        }
    }
}
