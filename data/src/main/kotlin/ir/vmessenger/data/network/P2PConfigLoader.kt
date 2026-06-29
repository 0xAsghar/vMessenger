package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.datastore.P2PFlagSnapshot
import ir.vmessenger.core.datastore.P2PPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads persisted P2P flags into the process-wide [P2PConfig] at startup and
 * mirrors runtime changes back to DataStore.
 */
@Singleton
class P2PConfigLoader @Inject constructor(
    private val p2pPreferences: P2PPreferences,
) {
    suspend fun loadIntoConfig() {
        applySnapshot(p2pPreferences.load())
    }

    suspend fun persistFromConfig() {
        p2pPreferences.save(currentSnapshot())
    }

    suspend fun resetToDefaults() {
        P2PConfig.resetToDefaults()
        p2pPreferences.resetToDefaults()
    }

    fun applySnapshot(snapshot: P2PFlagSnapshot) {
        P2PConfig.multiNodeEnabled = snapshot.multiNodeEnabled
        P2PConfig.peerCacheEnabled = snapshot.peerCacheEnabled
        P2PConfig.peerExchangeEnabled = snapshot.peerExchangeEnabled
        P2PConfig.dhtParticipationEnabled = snapshot.dhtParticipationEnabled
        P2PConfig.relayPeerModeEnabled = snapshot.relayPeerModeEnabled
        P2PConfig.natTraversalEnabled = snapshot.natTraversalEnabled
        P2PConfig.storeAndForwardEnabled = snapshot.storeAndForwardEnabled
        P2PConfig.reduceDefaultRelayEnabled = snapshot.reduceDefaultRelayEnabled
    }

    fun currentSnapshot(): P2PFlagSnapshot = P2PFlagSnapshot(
        multiNodeEnabled = P2PConfig.multiNodeEnabled,
        peerCacheEnabled = P2PConfig.peerCacheEnabled,
        peerExchangeEnabled = P2PConfig.peerExchangeEnabled,
        dhtParticipationEnabled = P2PConfig.dhtParticipationEnabled,
        relayPeerModeEnabled = P2PConfig.relayPeerModeEnabled,
        natTraversalEnabled = P2PConfig.natTraversalEnabled,
        storeAndForwardEnabled = P2PConfig.storeAndForwardEnabled,
        reduceDefaultRelayEnabled = P2PConfig.reduceDefaultRelayEnabled,
    )
}
