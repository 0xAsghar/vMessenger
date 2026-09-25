package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.RelayDns
import ir.vmessenger.core.common.network.RelayRejection
import ir.vmessenger.core.common.network.WebSocketFrameClient
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.RelayTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

fun interface InboundConnectionHandler {
    suspend fun onInboundConnection(connection: Connection)
}

/**
 * How one control-channel session ended, and how long to wait before dialling
 * again. [CLOSED] is the ordinary idle-timeout drop and has to be quick, because
 * the device is unreachable until the next session is up. The other two are
 * conditions only a person can resolve, and hammering them makes things worse:
 * two devices holding the same identity would evict each other once a second
 * forever, and a proof refused for its timestamp is refused again the moment it
 * is rebuilt.
 */
private enum class ControlChannelEnd(val pauseMs: Long) {
    CLOSED(1_000L),
    STALE_PROOF(60_000L),
    REPLACED(5 * 60_000L),
}

@Singleton
@Suppress("TooManyFunctions") // one control channel's lifecycle, plus where each circuit it announces goes
class RelayListener @Inject constructor(
    private val relayTransport: RelayTransport,
    private val relayHelloFactory: RelayHelloFactory,
    private val relayDirectory: RelayDirectory,
) {
    private var scope = newScope()
    private var handler: InboundConnectionHandler? = null

    @Volatile
    private var identityHash: ByteArray? = null

    @Volatile
    private var identityPub: ByteArray? = null

    /** Asked for the signing key each time a listener hello is built; the listener never stores it. */
    @Volatile
    private var ed25519PrivateKeyProvider: (suspend () -> ByteArray?)? = null

    @Volatile
    private var running = false

    /** Circuits a local component asked for by name; see [claimCircuits]. */
    private val claims = ConcurrentHashMap<String, InboundConnectionHandler>()

    /**
     * The relay this device can be reached on right now: the one its control channel is connected
     * to, or null between sessions. What a peer has to dial to get a circuit to this listener.
     */
    @Volatile
    var connectedRelayUrl: String? = null
        private set

    /**
     * [ed25519PrivateKeyProvider] is consulted per connection attempt so the key
     * lives only in its owner (the identity cache) and a wipe there is enough.
     */
    fun configure(
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKeyProvider: suspend () -> ByteArray?,
        inboundHandler: InboundConnectionHandler,
    ) {
        this.identityHash = identityHash
        this.identityPub = identityPub
        this.ed25519PrivateKeyProvider = ed25519PrivateKeyProvider
        this.handler = inboundHandler
    }

    fun start() {
        if (running) return
        running = true
        if (!scope.isActive) {
            scope = newScope()
        }
        AppLogger.info(TAG, "listener starting")
        scope.launch { maintainControlChannel() }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    /**
     * Routes every incoming circuit whose id starts with [prefix] to [handler] instead of the
     * messaging handshake, until [releaseCircuits].
     *
     * A dialer chooses its circuit id and the relay passes it through verbatim, which is what lets
     * a call's audio arrive on this listener without being mistaken for a messaging session: both
     * ends derive the prefix from the call's own key, so nobody else can name a circuit into it.
     */
    fun claimCircuits(prefix: String, handler: InboundConnectionHandler) {
        claims[prefix] = handler
    }

    fun releaseCircuits(prefix: String) {
        claims.remove(prefix)
    }

    @Suppress("TooGenericExceptionCaught") // any failure of the control channel is retried with backoff
    private suspend fun maintainControlChannel() {
        var backoffMs = 1_000L
        while (running && scope.isActive) {
            val credentials = credentials()
            if (credentials == null) {
                delay(1_000)
                continue
            }
            val selected = relayDirectory.activeRelay()
            val url = selected.url
            try {
                AppLogger.info(TAG, "control channel connecting via $url")
                val end = connectControlChannel(url, credentials)
                relayDirectory.reportResult(url, ok = true)
                NetworkPathTracker.reportConnectionSuccess()
                backoffMs = 1_000L
                AppLogger.info(TAG, "control channel ended (${end.name}), reconnecting in ${end.pauseMs}ms")
                delay(end.pauseMs)
            } catch (e: Exception) {
                relayDirectory.reportResult(url, ok = false)
                NetworkPathTracker.reportConnectionError(e)
                AppLogger.warn(TAG, "control channel lost ($url): ${e.message}, retry in ${backoffMs}ms")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** The three identity parts of a listener hello, or null while any of them is still missing. */
    private class Credentials(
        val identityHash: ByteArray,
        val identityPub: ByteArray,
        val ed25519PrivateKey: ByteArray,
    )

    private suspend fun credentials(): Credentials? {
        val hash = identityHash
        val pub = identityPub
        val key = ed25519PrivateKeyProvider?.let { provider -> runCatching { provider() }.getOrNull() }
        return if (hash == null || pub == null || key == null) null else Credentials(hash, pub, key)
    }

    @Suppress("TooGenericExceptionCaught") // per-IP attempt: any failure moves on to the next backend
    private suspend fun connectControlChannel(url: String, credentials: Credentials): ControlChannelEnd {
        val host = RelayDns.hostFromUrl(url)
        val ips = host?.let { RelayDns.candidateIps(it) }.orEmpty()
        if (host == null || ips.isEmpty()) {
            return connectControlChannelOnce(url, targetIp = null, credentials)
        }
        var lastError: Exception? = null
        for (ip in ips) {
            try {
                return connectControlChannelOnce(url, ip, credentials)
            } catch (e: Exception) {
                lastError = e
                AppLogger.warn(TAG, "control channel failed via $ip: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Relay control channel failed")
    }

    private suspend fun connectControlChannelOnce(
        url: String,
        targetIp: String?,
        credentials: Credentials,
    ): ControlChannelEnd {
        val hello = relayHelloFactory.buildListenerHello(
            credentials.identityHash,
            credentials.identityPub,
            credentials.ed25519PrivateKey,
        )
        val session = ControlChannelSession(url, hello)
        val webSocket = WebSocketFrameClient.openWebSocket(url, targetIp, session)
        try {
            session.openLatch.await()
            connectedRelayUrl = url
            val keepAlive = scope.launch { keepAliveLoop(webSocket, session.closeLatch) }
            session.closeLatch.await()
            keepAlive.cancel()
        } finally {
            connectedRelayUrl = null
            webSocket.cancel()
        }
        return session.end
    }

    /**
     * The listener control channel is idle most of the time (it only carries a
     * frame when a dialer arrives), so CDNs/proxies close it on their read
     * timeout — observed dropping every ~100s on mobile, leaving the device
     * briefly unreachable during each reconnect. Push a tiny app-level frame so
     * the path sees traffic. The relay server reads and ignores any non-close
     * frame on a listener session, so this is a safe no-op server-side.
     */
    private suspend fun keepAliveLoop(webSocket: WebSocket, closeLatch: CompletableDeferred<Unit>) {
        var alive = true
        while (alive && !closeLatch.isCompleted) {
            delay(KEEPALIVE_INTERVAL_MS)
            alive = !closeLatch.isCompleted && webSocket.send(KEEPALIVE_FRAME.toByteString())
            // Keeping the slot for a whole interval is the only acknowledgement the
            // relay ever gives a listener, so it is also what retires a stale banner.
            if (alive) NetworkPathTracker.reportListenerAccepted()
        }
    }

    /**
     * Accepts one relayed circuit and hands it to the inbound handler. Nothing
     * here may escape: a failing relay dial or a throwing handler is logged and
     * the circuit dropped, never the listener (or the process).
     */
    internal suspend fun acceptCircuit(url: String, circuitId: String): Boolean {
        val claimed = claims.entries.firstOrNull { circuitId.startsWith(it.key) }?.value
        val inbound = claimed ?: handler ?: return false
        val hello = relayHelloFactory.buildAcceptHello(circuitId)
        val connection = runCatching { relayTransport.openRelayCircuit(url, hello, awaitReady = true) }
            .onFailure { AppLogger.warn(TAG, "accept circuit $circuitId failed: ${it.message}") }
            .getOrNull()
        return connection != null &&
            runCatching { inbound.onInboundConnection(connection) }
                .onFailure {
                    AppLogger.warn(TAG, "inbound circuit $circuitId handler failed: ${it.message}")
                    runCatching { connection.close() }
                }
                .isSuccess
    }

    /**
     * One control-channel socket. The relay acknowledges nothing, so everything
     * read here is either an incoming circuit, a typed rejection, or the close —
     * and [end] is what the maintain loop does about it.
     */
    private inner class ControlChannelSession(
        private val url: String,
        private val hello: RelayHello,
    ) : WebSocketListener() {
        val openLatch = CompletableDeferred<Unit>()
        val closeLatch = CompletableDeferred<Unit>()

        @Volatile
        var end: ControlChannelEnd = ControlChannelEnd.CLOSED
            private set

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(hello.toByteArray().toByteString())
            AppLogger.info(TAG, "control channel connected")
            openLatch.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val event = runCatching { RelayEvent.parseFrom(bytes.toByteArray()) }.getOrNull() ?: return
            when (event.type) {
                RelayEventType.RELAY_EVENT_TYPE_INCOMING -> {
                    AppLogger.info(TAG, "incoming circuit ${event.circuitId}")
                    scope.launch { acceptCircuit(url, event.circuitId) }
                }
                RelayEventType.RELAY_EVENT_TYPE_ERROR -> onRelayError(webSocket, event.message)
                else -> Unit
            }
        }

        /**
         * A rejection the relay states out loud. A stale proof is the one the user
         * can do something about — this device's clock is outside the relay's skew
         * window, which also has discovery refusing its records — so it is published
         * instead of disappearing into the retry loop.
         */
        private fun onRelayError(webSocket: WebSocket, message: String) {
            AppLogger.warn(TAG, "relay error: $message")
            if (RelayRejection.isStaleProof(message)) {
                end = ControlChannelEnd.STALE_PROOF
                NetworkPathTracker.reportListenerRejected(message)
            }
            webSocket.close(NORMAL_CLOSE, message)
        }

        /**
         * The relay closes with "replaced" when a second device installs a listener
         * for the same identity. Answering the close is also what turns it into an
         * [onClosed]: without a reply OkHttp leaves the socket half-open until a
         * keepalive write eventually fails, which is up to a whole interval of
         * looking connected while nothing can arrive.
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (RelayRejection.isReplaced(reason)) {
                end = ControlChannelEnd.REPLACED
                NetworkPathTracker.reportListenerReplaced()
                AppLogger.warn(TAG, "listener slot taken over by another device holding this identity")
            }
            webSocket.close(NORMAL_CLOSE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!openLatch.isCompleted) {
                openLatch.completeExceptionally(
                    IllegalStateException("closed before open: $code $reason"),
                )
            }
            closeLatch.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!openLatch.isCompleted) {
                openLatch.completeExceptionally(t)
            }
            closeLatch.complete(Unit)
        }
    }

    companion object {
        private const val TAG = "Relay"
        private const val KEEPALIVE_INTERVAL_MS = 40_000L
        private const val NORMAL_CLOSE = 1000
        private val KEEPALIVE_FRAME = byteArrayOf(0)

        private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(TAG))
    }
}
