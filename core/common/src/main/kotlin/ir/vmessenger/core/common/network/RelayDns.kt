package ir.vmessenger.core.common.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps relay WebSocket connections on a stable backend IP when the relay
 * hostname resolves to multiple addresses (common behind round-robin DNS).
 *
 * Listeners pin to the first IP that accepts a control channel; dialers try every
 * known IP until the peer is found or all candidates fail.
 */
object RelayDns {
    private val pinnedIpByHost = ConcurrentHashMap<String, String>()

    val defaultDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookupPinnedOrSystem(hostname)
    }

    fun hostFromUrl(url: String): String? =
        runCatching { URI(url).host }.getOrNull()

    fun lookupPinnedOrSystem(hostname: String): List<InetAddress> {
        pinnedIpByHost[hostname]?.let { pinned ->
            return listOf(InetAddress.getByName(pinned))
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    fun candidateIps(hostname: String): List<String> {
        val resolved = runCatching {
            InetAddress.getAllByName(hostname)
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
        val pinned = pinnedIpByHost[hostname]
        return if (pinned != null) {
            listOf(pinned) + resolved.filter { it != pinned }
        } else {
            resolved
        }
    }

    fun pin(hostname: String, ip: String) {
        pinnedIpByHost[hostname] = ip
    }

    fun pinnedIp(hostname: String): String? = pinnedIpByHost[hostname]

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
