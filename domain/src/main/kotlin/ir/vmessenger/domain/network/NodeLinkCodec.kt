package ir.vmessenger.domain.network

import ir.vmessenger.domain.model.NetworkNodeRole

data class NodeLink(
    val role: NetworkNodeRole,
    val address: String,
)

/**
 * Compact, shareable text/QR encoding for network nodes so communities can hand
 * out their bootstrap/relay nodes (docs/P2P-Phases.md Phase 2).
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
