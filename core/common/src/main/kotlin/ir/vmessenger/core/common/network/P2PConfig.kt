package ir.vmessenger.core.common.network

/**
 * Runtime feature flags that guard the staged migration from the relay-assisted
 * design toward a serverless peer-to-peer network (see docs/P2P-Phases.md).
 *
 * Every experimental P2P path is gated here so it can be enabled/disabled at
 * runtime (debug screen) without rebuilding. The default relay remains available
 * as a last-resort fallback when demotion is enabled.
 *
 * As of v0.1.0-rc21, all migration phases are enabled by default. Disable
 * individual flags in Debug if you need a conservative baseline for testing.
 */
object P2PConfig {
    /** Phase 1: try multiple bootstrap/relay nodes instead of a single hardcoded one. */
    @Volatile
    var multiNodeEnabled: Boolean = true

    /** Phase 3: consult the local verified peer/DHT-node cache before public infrastructure. */
    @Volatile
    var peerCacheEnabled: Boolean = true

    /** Phase 4: exchange signed network-node records after a secure handshake. */
    @Volatile
    var peerExchangeEnabled: Boolean = true

    /** Phase 5: act as a minimal DHT participant (store/serve signed endpoint records). */
    @Volatile
    var dhtParticipationEnabled: Boolean = true

    /** Phase 6: forward opaque encrypted frames for other peers (relay-capable peer). */
    @Volatile
    var relayPeerModeEnabled: Boolean = true

    /** Phase 7: attempt NAT traversal / direct UDP before falling back to relay. */
    @Volatile
    var natTraversalEnabled: Boolean = true

    /** Phase 8: store-and-forward sealed blobs through trusted mailbox peers. */
    @Volatile
    var storeAndForwardEnabled: Boolean = true

    /** Phase 9: demote the default relay to a last-resort path. */
    @Volatile
    var reduceDefaultRelayEnabled: Boolean = true

    /**
     * Resets every flag to its default. Used by tests and the secure-wipe flow.
     */
    fun resetToDefaults() {
        multiNodeEnabled = true
        peerCacheEnabled = true
        peerExchangeEnabled = true
        dhtParticipationEnabled = true
        relayPeerModeEnabled = true
        natTraversalEnabled = true
        storeAndForwardEnabled = true
        reduceDefaultRelayEnabled = true
    }
}
