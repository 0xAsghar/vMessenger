package ir.vmessenger.core.common.network

import java.net.URI
import java.net.URISyntaxException

/** Why [NodeAddressPolicy] refused an address; the repository maps this to a user-facing message. */
enum class NodeAddressRejection {
    BLANK,

    /** Not a parseable `wss://`/`ws://` URL with a host (relay and bootstrap). */
    MALFORMED,

    /** `ws://` or plain `host:port` for a host that is not local, or in a release build. */
    INSECURE_NOT_LOCAL,
}

/**
 * Decides which node addresses the app may store or dial.
 *
 * Release builds accept only `wss://host[:port][/path]` for relays and bootstrap
 * nodes. When [allowInsecureLocal] is set (debug builds, from `BuildConfig.DEBUG`)
 * a relay may also be `ws://` and a bootstrap node may be `ws://` or `host:port`,
 * but only for local hosts: `10.0.2.2`, `127.0.0.1`, `localhost` and RFC 1918
 * ranges. Applied by the node repository (add + every import) and by the
 * transports before dialing, so a stored row from an older build cannot bypass it.
 */
class NodeAddressPolicy(val allowInsecureLocal: Boolean) {
    fun checkRelay(address: String): NodeAddressRejection? = checkWebSocketUrl(address.trim())

    fun checkBootstrap(address: String): NodeAddressRejection? {
        val trimmed = address.trim()
        val hostPort = hostPortPattern.matchEntire(trimmed)
        return when {
            trimmed.isEmpty() -> NodeAddressRejection.BLANK
            hostPort == null -> checkWebSocketUrl(trimmed)
            hostPort.groupValues[2].toInt() !in 1..MAX_PORT -> NodeAddressRejection.MALFORMED
            allowInsecureLocal && isLocalHost(hostPort.groupValues[1]) -> null
            else -> NodeAddressRejection.INSECURE_NOT_LOCAL
        }
    }

    fun isRelayAllowed(address: String): Boolean = checkRelay(address) == null

    fun isBootstrapAllowed(address: String): Boolean = checkBootstrap(address) == null

    private fun checkWebSocketUrl(trimmed: String): NodeAddressRejection? {
        val uri = parse(trimmed)
        val host = uri?.host?.takeIf { it.isNotBlank() }
        return when {
            trimmed.isEmpty() -> NodeAddressRejection.BLANK
            uri == null || host == null || uri.userInfo != null -> NodeAddressRejection.MALFORMED
            uri.scheme.equals(SCHEME_WSS, ignoreCase = true) -> null
            !uri.scheme.equals(SCHEME_WS, ignoreCase = true) -> NodeAddressRejection.MALFORMED
            allowInsecureLocal && isLocalHost(host) -> null
            else -> NodeAddressRejection.INSECURE_NOT_LOCAL
        }
    }

    private fun parse(address: String): URI? =
        try {
            URI(address)
        } catch (_: URISyntaxException) {
            null
        }

    companion object {
        /** Secure-by-default policy; the app replaces it at startup from `BuildConfig.DEBUG`. */
        val RELEASE = NodeAddressPolicy(allowInsecureLocal = false)

        /** Process-wide policy consulted by transports and the node repository. */
        @Volatile
        var current: NodeAddressPolicy = RELEASE

        private const val SCHEME_WS = "ws"
        private const val SCHEME_WSS = "wss"
        private const val MAX_PORT = 65_535
        private const val IPV4_OCTETS = 4
        private const val OCTET_MAX = 255
        private val hostPortPattern = Regex("""^([A-Za-z0-9.\-]+):(\d{1,5})$""")
        private val localNames = setOf("10.0.2.2", "127.0.0.1", "localhost")

        /** `10.0.2.2`, loopback, `localhost`, or an RFC 1918 private IPv4 address. */
        fun isLocalHost(host: String): Boolean {
            val normalized = host.trim().lowercase()
            val parts = normalized.split('.').map { it.toIntOrNull() }
            val octets = parts.filterNotNull()
            val ipv4 = octets.size == IPV4_OCTETS && parts.size == IPV4_OCTETS && octets.all { it in 0..OCTET_MAX }
            return normalized in localNames || (ipv4 && isPrivateIpv4(octets))
        }

        /** RFC 1918 (10/8, 172.16/12, 192.168/16) plus loopback 127/8. */
        private fun isPrivateIpv4(o: List<Int>): Boolean =
            o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                o[0] == 127
    }
}
