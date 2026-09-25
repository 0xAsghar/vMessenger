package ir.vmessenger.core.common.network

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps relay WebSocket connections on a stable backend IP when the relay
 * hostname resolves to multiple addresses (common behind round-robin DNS).
 *
 * Listeners stick to the first IP that accepts a control channel; dialers try every
 * known IP until the peer is found or all candidates fail. "Sticky", not "pinned":
 * a pin, in this codebase, is a certificate key ([SpkiPin]).
 */
object RelayDns {
    private val stickyIpByHost = ConcurrentHashMap<String, String>()

    val defaultDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookupStickyOrSystem(hostname)
    }

    /** The host of a node URL — lowercase, an IPv6 address without brackets — or null. */
    fun hostFromUrl(url: String): String? = NodeUrl.parse(url)?.host

    fun lookupStickyOrSystem(hostname: String): List<InetAddress> {
        stickyIpByHost[hostname]?.let { sticky ->
            return listOf(InetAddress.getByName(sticky))
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    fun candidateIps(hostname: String): List<String> {
        val resolved = runCatching {
            InetAddress.getAllByName(hostname)
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
        val sticky = stickyIpByHost[hostname]
        return if (sticky != null) {
            listOf(sticky) + resolved.filter { it != sticky }
        } else {
            resolved
        }
    }

    fun stick(hostname: String, ip: String) {
        stickyIpByHost[hostname] = ip
    }

    fun stickyIp(hostname: String): String? = stickyIpByHost[hostname]

    /** Forgets every sticky backend IP (secure wipe, and tests). */
    fun clearStickyIps() {
        stickyIpByHost.clear()
    }

    fun dnsTargeting(hostname: String, ip: String): Dns = object : Dns {
        override fun lookup(requested: String): List<InetAddress> =
            if (requested.equals(hostname, ignoreCase = true)) {
                listOf(InetAddress.getByName(ip))
            } else {
                Dns.SYSTEM.lookup(requested)
            }
    }

    fun isPeerNotListening(message: String?): Boolean =
        message?.contains("Peer not listening on relay", ignoreCase = true) == true
}
