package ir.vmessenger.core.datastore

import ir.vmessenger.core.common.network.P2PConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2PFlagDefaultsTest {
    @Test
    fun snapshotDefaultsMatchRuntimeConfigDefaults() {
        P2PConfig.resetToDefaults()
        val snapshot = P2PFlagSnapshot()
        assertEquals(P2PConfig.multiNodeEnabled, snapshot.multiNodeEnabled)
        assertEquals(P2PConfig.peerCacheEnabled, snapshot.peerCacheEnabled)
        assertEquals(P2PConfig.peerExchangeEnabled, snapshot.peerExchangeEnabled)
        assertEquals(P2PConfig.dhtParticipationEnabled, snapshot.dhtParticipationEnabled)
        assertEquals(P2PConfig.relayPeerModeEnabled, snapshot.relayPeerModeEnabled)
        assertEquals(P2PConfig.natTraversalEnabled, snapshot.natTraversalEnabled)
        assertEquals(P2PConfig.storeAndForwardEnabled, snapshot.storeAndForwardEnabled)
        assertEquals(P2PConfig.reduceDefaultRelayEnabled, snapshot.reduceDefaultRelayEnabled)
    }

    @Test
    fun onlyMultiNodeAndPeerCacheAreOnByDefault() {
        val snapshot = P2PFlagSnapshot()
        assertTrue(snapshot.multiNodeEnabled)
        assertTrue(snapshot.peerCacheEnabled)
        assertFalse(snapshot.peerExchangeEnabled)
        assertFalse(snapshot.dhtParticipationEnabled)
        assertFalse(snapshot.relayPeerModeEnabled)
        assertFalse(snapshot.natTraversalEnabled)
        assertFalse(snapshot.storeAndForwardEnabled)
        assertFalse(snapshot.reduceDefaultRelayEnabled)
    }
}
