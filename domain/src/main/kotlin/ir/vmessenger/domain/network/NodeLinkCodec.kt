package ir.vmessenger.domain.network

import ir.vmessenger.core.common.network.NodeUrl
import ir.vmessenger.domain.model.NetworkNodeRole

data class NodeLink(
    val role: NetworkNodeRole,
    val address: String,
)

/**
 * Compact, shareable text/QR encoding for network nodes so communities can hand
 * out their bootstrap/relay nodes.
 *
 * Format: `vmnode:<role>:<address>` e.g. `vmnode:relay:wss://relay.example/relay`.
 * The address is everything after the role, so it may freely contain ':' and '/'.
 */
object NodeLinkCodec {
    const val SCHEME = "vmnode"

    fun encode(role: NetworkNodeRole, address: String): String = "$SCHEME:${role.wire()}:$address"

    fun decode(text: String): NodeLink? {
        val trimmed = text.trim()
        val prefix = "$SCHEME:"
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
        val rest = trimmed.substring(prefix.length)
        val sep = rest.indexOf(':')
        if (sep <= 0) return null
        val role = parseRole(rest.substring(0, sep)) ?: return null
        val address = rest.substring(sep + 1).trim()
        if (address.isBlank()) return null
        return NodeLink(role = role, address = address)
    }

    /**
     * The other half of a vMessenger node. Every node serves its DHT at `/dht` and its relay at
     * `/relay` on one host, port and key, so either address names both. Null for an address on any
     * other path: a node run some other way, whose other half the person adds by hand.
     */
    fun companionOf(role: NetworkNodeRole, address: String): NodeLink? {
        val other = when (role) {
            NetworkNodeRole.BOOTSTRAP -> NetworkNodeRole.RELAY
            NetworkNodeRole.RELAY -> NetworkNodeRole.BOOTSTRAP
        }
        return NodeUrl.parse(address)
            ?.takeIf { it.query == null && it.path == role.path() }
            ?.let { NodeLink(role = other, address = it.withPath(other.path())) }
    }

    /**
     * The role a plain address names by its path: `/dht` a bootstrap node, `/relay` a relay. Null for
     * a `vmnode:` link (its own role decides) and for any other path.
     */
    fun roleOfPath(input: String): NetworkNodeRole? =
        NodeUrl.parse(input)?.takeIf { decode(input) == null }?.path?.let { path ->
            NetworkNodeRole.entries.firstOrNull { it.path() == path }
        }

    private fun NetworkNodeRole.path(): String = when (this) {
        NetworkNodeRole.BOOTSTRAP -> "/dht"
        NetworkNodeRole.RELAY -> "/relay"
    }

    private fun NetworkNodeRole.wire(): String = when (this) {
        NetworkNodeRole.BOOTSTRAP -> "bootstrap"
        NetworkNodeRole.RELAY -> "relay"
    }

    private fun parseRole(value: String): NetworkNodeRole? = when (value.trim().lowercase()) {
        "bootstrap", "dht" -> NetworkNodeRole.BOOTSTRAP
        "relay" -> NetworkNodeRole.RELAY
        else -> null
    }
}
