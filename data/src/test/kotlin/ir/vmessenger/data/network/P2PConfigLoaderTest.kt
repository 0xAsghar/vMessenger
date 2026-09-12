package ir.vmessenger.data.network

import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.datastore.P2PFlagSnapshot
import ir.vmessenger.core.datastore.P2PPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun defaultsAgreeAcrossConfigPreferencesAndSnapshot() {
        val snapshot = P2PFlagSnapshot()
        assertEquals(snapshot.multiNodeEnabled, P2PConfig.multiNodeEnabled)
        assertEquals(snapshot.peerCacheEnabled, P2PConfig.peerCacheEnabled)
        assertEquals(snapshot.peerExchangeEnabled, P2PConfig.peerExchangeEnabled)
        assertEquals(snapshot.dhtParticipationEnabled, P2PConfig.dhtParticipationEnabled)
        assertEquals(snapshot.relayPeerModeEnabled, P2PConfig.relayPeerModeEnabled)
        assertEquals(snapshot.natTraversalEnabled, P2PConfig.natTraversalEnabled)
        assertEquals(snapshot.storeAndForwardEnabled, P2PConfig.storeAndForwardEnabled)
        assertEquals(snapshot.reduceDefaultRelayEnabled, P2PConfig.reduceDefaultRelayEnabled)
        assertTrue(P2PConfig.multiNodeEnabled)
        assertTrue(P2PConfig.peerCacheEnabled)
        assertFalse(P2PConfig.peerExchangeEnabled)
        assertFalse(P2PConfig.dhtParticipationEnabled)
        assertFalse(P2PConfig.relayPeerModeEnabled)
        assertFalse(P2PConfig.natTraversalEnabled)
        assertFalse(P2PConfig.storeAndForwardEnabled)
        assertFalse(P2PConfig.reduceDefaultRelayEnabled)
        assertEquals(P2PPreferences.P2P_DEFAULT_MULTI_NODE, P2PConfig.DEFAULT_MULTI_NODE)
        assertEquals(P2PPreferences.P2P_DEFAULT_PEER_CACHE, P2PConfig.DEFAULT_PEER_CACHE)
        assertEquals(P2PPreferences.P2P_DEFAULT_PEER_EXCHANGE, P2PConfig.DEFAULT_PEER_EXCHANGE)
        assertEquals(P2PPreferences.P2P_DEFAULT_DHT, P2PConfig.DEFAULT_DHT_PARTICIPATION)
        assertEquals(P2PPreferences.P2P_DEFAULT_RELAY_PEER, P2PConfig.DEFAULT_RELAY_PEER_MODE)
        assertEquals(P2PPreferences.P2P_DEFAULT_NAT, P2PConfig.DEFAULT_NAT_TRAVERSAL)
        assertEquals(P2PPreferences.P2P_DEFAULT_STORE_FORWARD, P2PConfig.DEFAULT_STORE_AND_FORWARD)
        assertEquals(P2PPreferences.P2P_DEFAULT_REDUCE_RELAY, P2PConfig.DEFAULT_REDUCE_DEFAULT_RELAY)
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
