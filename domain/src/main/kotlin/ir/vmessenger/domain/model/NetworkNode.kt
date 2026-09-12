package ir.vmessenger.domain.model

import ir.vmessenger.core.common.network.NodeTrust

enum class NetworkNodeRole {
    /** A bootstrap/DHT node used for discovery. */
    BOOTSTRAP,

    /** A relay node used to forward encrypted frames. */
    RELAY,
}

/**
 * A user-visible network node (bootstrap or relay). Surfaces health so users can
 * see which nodes work and manage their own community infrastructure
 * (docs/P2P-Phases.md Phase 2).
 */
data class NetworkNode(
    val address: String,
    val role: NetworkNodeRole,
    val source: String,
    val enabled: Boolean,
    val lastOkUnixMs: Long?,
    val lastFailUnixMs: Long?,
    val failCount: Int,
    /**
     * Trust level. [NodeTrust.COMMUNITY] nodes (peer exchange / DHT) are imported disabled;
     * the Nodes screen shows them as "community (disabled)" with an enable toggle.
     */
    val trust: NodeTrust,
) {
    val builtIn: Boolean get() = source == SOURCE_BUILT_IN

    val community: Boolean get() = trust == NodeTrust.COMMUNITY

    companion object {
        const val SOURCE_BUILT_IN = "BUILT_IN"
        const val SOURCE_USER = "USER"
        const val SOURCE_PEER_EXCHANGE = "PEER_EXCHANGE"
    }
}
