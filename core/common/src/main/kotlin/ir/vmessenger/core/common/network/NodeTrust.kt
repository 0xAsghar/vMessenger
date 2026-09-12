package ir.vmessenger.core.common.network

/**
 * How much the app trusts a bootstrap/relay node record. Persisted as the enum
 * name in `relay_node.trust` / `bootstrap_node.trust`.
 *
 * Only [BUILT_IN], [USER] and [OFFICIAL] nodes are enabled automatically;
 * [COMMUNITY] nodes learned from peers or the DHT are stored disabled until the
 * user turns them on (see [NodeRanking.autoEnabled]).
 */
enum class NodeTrust {
    /** Compiled into the app ([NetworkConfig]). */
    BUILT_IN,

    /** Added by the user in the Nodes screen or via a `vmnode:` link. */
    USER,

    /** Signed by the operator key ([NetworkConfig.OPERATOR_ED25519_PUBLIC_KEY_HEX]). */
    OFFICIAL,

    /** Learned from a peer or the DHT; never trusted without user consent. */
    COMMUNITY,
    ;

    companion object {
        /** Parses a persisted name, falling back to [COMMUNITY] for unknown values. */
        fun fromName(name: String?): NodeTrust = entries.firstOrNull { it.name == name } ?: COMMUNITY
    }
}
