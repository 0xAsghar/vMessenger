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

    /**
     * Which flags actually ship on, pinned so that turning one on is a deliberate edit here rather
     * than something that slips out in a release.
     *
     * Store-and-forward joined them in 1.1, when the third-party half was finally wired: before
     * that the flag only parked blobs in the sender's own database, so a message to an offline peer
     * was never delivered by it. Enabling it is a real trade — a host learns that someone holds a
     * message for a routing key, and mailbox blobs have no forward secrecy — and docs/Security.md
     * records that alongside this.
     */
    @Test
    fun onlyTheDeliberatelyEnabledFlagsAreOnByDefault() {
        val snapshot = P2PFlagSnapshot()
        assertTrue(snapshot.multiNodeEnabled)
        assertTrue(snapshot.peerCacheEnabled)
        assertTrue(snapshot.storeAndForwardEnabled)
        assertFalse(snapshot.peerExchangeEnabled)
        assertFalse(snapshot.dhtParticipationEnabled)
        assertFalse(snapshot.relayPeerModeEnabled)
        assertFalse(snapshot.natTraversalEnabled)
        assertFalse(snapshot.reduceDefaultRelayEnabled)
    }
}
