package ir.vmessenger.core.common.network

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * A node address: `wss://host[:port][/path]`, optionally ending in `#pin-sha256=<pin>[,<pin>…]`.
 *
 * The fragment carries the key pins of a node whose certificate no CA vouches for — one set up on a
 * bare IP address. It is part of the address string, so it travels wherever the address does (a
 * `vmnode:` link, a signed endpoint record, the relay a call falls back to), and a signature over
 * the address covers it. It is never sent: [dialUrl] leaves it off. An app older than pins sees an
 * ordinary `wss://` URL and fails its certificate check against the system CAs, which is the safe
 * way to not understand one.
 *
 * Up to [MAX_PINS] pins, for key rotation: a certificate matching any of them is the node.
 */
class NodeUrl private constructor(
    val scheme: String,
    /** Lowercase; an IPv6 address without brackets. */
    val host: String,
    /** -1 for the scheme's default, however it was written. */
    val port: Int,
    /** `""` or `/…`, as written. */
    val path: String,
    val query: String?,
    val pins: List<SpkiPin>,
) {
    val isPinned: Boolean get() = pins.isNotEmpty()

    /** What is actually dialled: no fragment. */
    val dialUrl: String = buildString {
        append(scheme).append("://").append(hostForUrl(host))
        if (port != -1) append(':').append(port)
        append(path)
        if (query != null) append('?').append(query)
    }

    /** One spelling per address: lowercase scheme and host, default port dropped, pins sorted. */
    val canonical: String = if (pins.isEmpty()) dialUrl else "$dialUrl#$PIN_PREFIX${sortedPinText()}"

    /**
     * Where the node is, whatever its key: `scheme://host:port/path` with the port always spelled.
     * Two addresses with the same location and different pins are one node whose key changed.
     */
    val locationKey: String =
        "$scheme://${hostForUrl(host)}:${if (port == -1) defaultPort(scheme) else port}$path"

    /** For people: the dialled URL, without the pin. */
    val displayText: String get() = dialUrl

    private fun sortedPinText(): String = pins.map { it.text }.sorted().joinToString(",")

    override fun equals(other: Any?): Boolean = other is NodeUrl && canonical == other.canonical

    override fun hashCode(): Int = canonical.hashCode()

    override fun toString(): String = canonical

    companion object {
        const val MAX_PINS = 4
        const val PIN_PREFIX = "pin-sha256="
        private const val SCHEME_WS = "ws"
        private const val SCHEME_WSS = "wss"
        private const val PORT_WS = 80
        private const val PORT_WSS = 443

        /** The address as a node URL, or null when it is not a `ws(s)://` URL with a valid pin spec. */
        fun parse(address: String): NodeUrl? {
            val uri = parseUri(address.trim()) ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == SCHEME_WS || it == SCHEME_WSS }
            val host = uri.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() && uri.userInfo == null }
            val pins = scheme?.let { pinsOf(uri.rawFragment, it) }
            return if (scheme == null || host == null || pins == null) {
                null
            } else {
                val port = if (uri.port == defaultPort(scheme)) -1 else uri.port
                NodeUrl(scheme, host, port, uri.rawPath.orEmpty(), uri.rawQuery, pins)
            }
        }

        /**
         * The pins a fragment names: empty for no fragment, null when the fragment is anything but
         * a valid pin spec on a `wss://` URL (a pin over plain `ws://` would pin nothing).
         */
        fun pinsOf(rawFragment: String?, scheme: String): List<SpkiPin>? {
            if (rawFragment.isNullOrEmpty()) return emptyList()
            val pins = rawFragment
                .takeIf { scheme.equals(SCHEME_WSS, ignoreCase = true) && it.startsWith(PIN_PREFIX) }
                ?.removePrefix(PIN_PREFIX)
                ?.split(',')
                ?.takeIf { it.size <= MAX_PINS }
                ?.map(SpkiPin::parse)
            return pins?.takeIf { list -> list.none { it == null } }?.filterNotNull()?.distinct()
        }

        /** Spells a node URL the way [parse] reads it back. */
        fun build(host: String, port: Int = -1, path: String, pins: List<SpkiPin> = emptyList()): String {
            require(pins.size <= MAX_PINS) { "at most $MAX_PINS pins" }
            val base = buildString {
                append(SCHEME_WSS).append("://").append(hostForUrl(host.lowercase(Locale.ROOT)))
                if (port != -1 && port != PORT_WSS) append(':').append(port)
                append(path)
            }
            val pinText = pins.map { it.text }.distinct().sorted().joinToString(",")
            return if (pins.isEmpty()) base else "$base#$PIN_PREFIX$pinText"
        }

        private fun hostForUrl(host: String): String = if (':' in host) "[$host]" else host

        private fun defaultPort(scheme: String): Int = if (scheme == SCHEME_WSS) PORT_WSS else PORT_WS

        private fun parseUri(address: String): URI? =
            try {
                URI(address)
            } catch (_: URISyntaxException) {
                null
            }
    }
}
