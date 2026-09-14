package ir.vmessenger.core.common.network

/**
 * Runtime feature flags that guard the staged migration from the relay-assisted
 * design toward a serverless peer-to-peer network.
 *
 * Every experimental P2P path is gated here so it can be enabled/disabled at
 * runtime (debug screen) without rebuilding. The default relay remains available
 * as a last-resort fallback when demotion is enabled.
 *
 * 1.0 defaults: only multi-node selection and the verified peer cache are on.
 * Peer exchange, DHT participation, relay-peer mode, UDP attempts, store-and-
 * forward and default-relay demotion are half-features that only leak or waste
 * time today; their code paths stay safe but off. These defaults must agree
 * with `P2PPreferences.P2P_DEFAULT_*` and `P2PFlagSnapshot` in core/datastore.
 */
object P2PConfig {
    const val DEFAULT_MULTI_NODE = true
    const val DEFAULT_PEER_CACHE = true
    const val DEFAULT_PEER_EXCHANGE = false
    const val DEFAULT_DHT_PARTICIPATION = false
    const val DEFAULT_RELAY_PEER_MODE = false
    const val DEFAULT_NAT_TRAVERSAL = false
    const val DEFAULT_STORE_AND_FORWARD = true
    const val DEFAULT_REDUCE_DEFAULT_RELAY = false

    /** Phase 1: try multiple bootstrap/relay nodes instead of a single hardcoded one. */
    @Volatile
    var multiNodeEnabled: Boolean = DEFAULT_MULTI_NODE

    /** Phase 3: consult the local verified peer/DHT-node cache before public infrastructure. */
    @Volatile
    var peerCacheEnabled: Boolean = DEFAULT_PEER_CACHE

    /** Phase 4: exchange bootstrap/relay address hints after a secure handshake (signed records). */
    @Volatile
    var peerExchangeEnabled: Boolean = DEFAULT_PEER_EXCHANGE

    /** Phase 5: act as a minimal DHT participant (store/serve signed endpoint records). */
    @Volatile
    var dhtParticipationEnabled: Boolean = DEFAULT_DHT_PARTICIPATION

    /** Phase 6: forward opaque encrypted frames for other peers (relay-capable peer). */
    @Volatile
    var relayPeerModeEnabled: Boolean = DEFAULT_RELAY_PEER_MODE

    /** Phase 7: UDP transport attempts (TCP→UDP mirroring; not full ICE/STUN NAT traversal). */
    @Volatile
    var natTraversalEnabled: Boolean = DEFAULT_NAT_TRAVERSAL

    /** Phase 8: store-and-forward sealed blobs through trusted mailbox peers. */
    @Volatile
    var storeAndForwardEnabled: Boolean = DEFAULT_STORE_AND_FORWARD

    /** Phase 9: demote the default relay to a last-resort path. */
    @Volatile
    var reduceDefaultRelayEnabled: Boolean = DEFAULT_REDUCE_DEFAULT_RELAY

    /**
     * Resets every flag to its default. Used by tests and the secure-wipe flow.
     */
    fun resetToDefaults() {
        multiNodeEnabled = DEFAULT_MULTI_NODE
        peerCacheEnabled = DEFAULT_PEER_CACHE
        peerExchangeEnabled = DEFAULT_PEER_EXCHANGE
        dhtParticipationEnabled = DEFAULT_DHT_PARTICIPATION
        relayPeerModeEnabled = DEFAULT_RELAY_PEER_MODE
        natTraversalEnabled = DEFAULT_NAT_TRAVERSAL
        storeAndForwardEnabled = DEFAULT_STORE_AND_FORWARD
        reduceDefaultRelayEnabled = DEFAULT_REDUCE_DEFAULT_RELAY
    }
}
