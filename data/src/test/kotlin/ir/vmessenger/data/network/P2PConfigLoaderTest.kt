package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.datastore.P2PFlagSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class P2PConfigLoaderTest {
    @Before
    fun reset() {
        P2PConfig.resetToDefaults()
    }

    @After
    fun tearDown() {
        P2PConfig.resetToDefaults()
    }

    @Test
    fun resetToDefaultsKeepsRelayPeerOff() {
        P2PConfig.relayPeerModeEnabled = true
        P2PConfig.resetToDefaults()
        assertFalse(P2PConfig.relayPeerModeEnabled)
    }

    @Test
    fun snapshotRoundTripMatchesP2PConfig() {
        P2PConfig.multiNodeEnabled = false
        P2PConfig.relayPeerModeEnabled = true
        val snapshot = P2PFlagSnapshot(
            multiNodeEnabled = P2PConfig.multiNodeEnabled,
            peerCacheEnabled = P2PConfig.peerCacheEnabled,
            peerExchangeEnabled = P2PConfig.peerExchangeEnabled,
            dhtParticipationEnabled = P2PConfig.dhtParticipationEnabled,
            relayPeerModeEnabled = P2PConfig.relayPeerModeEnabled,
            natTraversalEnabled = P2PConfig.natTraversalEnabled,
            storeAndForwardEnabled = P2PConfig.storeAndForwardEnabled,
            reduceDefaultRelayEnabled = P2PConfig.reduceDefaultRelayEnabled,
        )
        assertEquals(false, snapshot.multiNodeEnabled)
        assertEquals(true, snapshot.relayPeerModeEnabled)
    }
}
