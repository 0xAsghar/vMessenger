package ir.vmessenger.node

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.server.websocket.DefaultWebSocketServerSession as WsSession

/** A registered listener socket plus the bookkeeping the per-listener caps need. */
class ListenerEntry(
    val session: WsSession,
    val ip: String,
    listenerId: ByteArray,
) {
    /** Routing key (16-byte identity-hash prefix) dialers look the listener up by. */
    val key: String = IdentityHashMatcher.routingKeyHex(listenerId)

    /** Short, log-safe identifier (first 4 bytes of the identity hash). */
    val prefix: String = IdentityHashMatcher.hashPrefixHex(listenerId)

    /** Dialers currently waiting for this listener to accept. */
    val pending = AtomicInteger()
}

/** A dialer waiting for its target listener to open an ACCEPT socket for [circuitId]. */
class PendingDialer(
    val session: WsSession,
    val listener: ListenerEntry,
    val circuitId: String,
) {
    /** Completed by the accept side once the circuit has been bridged and torn down. */
    val finished = CompletableDeferred<Unit>()
}

/**
 * Everything the relay routes share: configuration, counters, the listener and
 * pending-dialer tables, rate limiters, the proof replay cache and the DHT store.
 *
 * Built once per process by [RelayNodeServer] and once per test by
 * `RelayNodeServerTest`, then handed to [relayNodeModule].
 */
class RelayNodeState(
    val config: NodeConfig,
    val nodeId: ByteArray,
    val clock: () -> Long = System::currentTimeMillis,
    val stats: NodeStats = NodeStats(clock = clock),
    val dht: DhtRequestHandler = newDhtHandler(config, nodeId, clock, stats),
) {
    val listeners = ConcurrentHashMap<String, ListenerEntry>()
    val pendingDialers = ConcurrentHashMap<String, PendingDialer>()
    val dialLimiter = RateLimiter(config.dialRatePerMin, config.dialBurst, clock = clock)
    val storeLimiter = RateLimiter(config.storeRatePerMin, config.storeBurst, clock = clock)

    /**
     * A proof with timestamp `ts` is accepted while `|now - ts| <= proofMaxSkewMs`,
     * so from its first sighting it can stay valid for up to twice the skew.
     */
    val replayCache = ReplayCache(ttlMs = 2 * config.proofMaxSkewMs)
    val sodium = LazySodiumJava(SodiumJava())

    private val listenersPerIp = ConcurrentHashMap<String, Int>()

    /**
     * Counts one more listener for [ip]; returns false (and counts nothing) when
     * that would exceed [allowance].
     */
    fun reserveListenerSlot(ip: String, allowance: Int): Boolean {
        val count = listenersPerIp.merge(ip, 1, Int::plus) ?: 1
        if (count <= allowance) return true
        releaseListenerSlot(ip)
        return false
    }

    fun releaseListenerSlot(ip: String) {
        listenersPerIp.computeIfPresent(ip) { _, n -> if (n <= 1) null else n - 1 }
    }

    /** Listeners currently counted for [ip] (for tests). */
    fun listenerCount(ip: String): Int = listenersPerIp[ip] ?: 0

    /** Closes every registered listener socket with [reason]; used on shutdown. */
    suspend fun closeAllListeners(reason: CloseReason) {
        val entries = listeners.values.toList()
        coroutineScope {
            for (entry in entries) {
                launch { entry.session.close(reason) }
            }
        }
    }

    companion object {
        fun newDhtHandler(
            config: NodeConfig,
            nodeId: ByteArray,
            clock: () -> Long,
            stats: NodeStats,
        ): DhtRequestHandler = DhtRequestHandler(
            nodeId = nodeId,
            advertisedAddress = config.advertisedDhtUrl,
            peerNodes = config.peerNodes,
            maxRecords = config.maxRecords,
            maxRecordTtlMs = config.maxRecordTtlMs,
            maxFutureSkewMs = config.proofMaxSkewMs,
            clock = clock,
            stats = stats.asDhtCounters(),
        )
    }
}
