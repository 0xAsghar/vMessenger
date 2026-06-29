package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.RelayPeerPolicy
import ir.vmessenger.network.messaging.PeerRelayService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 6: lifecycle coordinator for peer-operated relay mode.
 */
@Singleton
class PeerRelayCoordinator @Inject constructor(
    private val peerRelayService: PeerRelayService,
    private val relayPeerPolicyManager: RelayPeerPolicyManager,
) {
    fun isActive(): Boolean = P2PConfig.relayPeerModeEnabled && peerRelayService.isAcceptingCircuits()

    fun logStatus() {
        peerRelayService.policy = relayPeerPolicyManager.policy
        val policy = relayPeerPolicyManager.policy
        NetworkPathTracker.setRelayPeerStatus(isActive(), policy)
        if (P2PConfig.relayPeerModeEnabled) {
            AppLogger.info(
                "PeerRelay",
                "relay-peer mode policy=$policy circuits=${peerRelayService.activeCircuitCount()}",
            )
        }
    }

    fun setPolicy(policy: RelayPeerPolicy) {
        relayPeerPolicyManager.policy = policy
        NetworkPathTracker.setRelayPeerStatus(isActive(), policy)
    }
}
