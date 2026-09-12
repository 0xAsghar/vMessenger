package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.RelayDns
import ir.vmessenger.core.common.network.WebSocketFrameClient
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import javax.inject.Inject
import javax.inject.Singleton

fun interface InboundConnectionHandler {
    suspend fun onInboundConnection(connection: Connection)
}

@Singleton
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

    @Suppress("TooGenericExceptionCaught") // any failure of the control channel is retried with backoff
    private suspend fun maintainControlChannel() {
        var backoffMs = 1_000L
        while (running && scope.isActive) {
            val hash = identityHash
            val pub = identityPub
            val key = ed25519PrivateKeyProvider?.let { provider -> runCatching { provider() }.getOrNull() }
            if (hash == null || pub == null || key == null) {
                delay(1_000)
                continue
            }
            val selected = relayDirectory.activeRelay()
            val url = selected.url
            try {
                AppLogger.info(TAG, "control channel connecting via $url")
                connectControlChannel(url, hash, pub, key)
                relayDirectory.reportResult(url, ok = true)
                NetworkPathTracker.reportConnectionSuccess()
                backoffMs = 1_000L
                AppLogger.info(TAG, "control channel ended, reconnecting in ${backoffMs}ms")
                delay(backoffMs)
            } catch (e: Exception) {
                relayDirectory.reportResult(url, ok = false)
                NetworkPathTracker.reportConnectionError(e)
                AppLogger.warn(TAG, "control channel lost ($url): ${e.message}, retry in ${backoffMs}ms")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // per-IP attempt: any failure moves on to the next backend
    private suspend fun connectControlChannel(
        url: String,
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKey: ByteArray,
    ) {
        val host = RelayDns.hostFromUrl(url)
        val ips = host?.let { RelayDns.candidateIps(it) }.orEmpty()
        if (host == null || ips.isEmpty()) {
            connectControlChannelOnce(url, host, targetIp = null, identityHash, identityPub, ed25519PrivateKey)
            return
        }
        var lastError: Exception? = null
        for (ip in ips) {
            try {
                connectControlChannelOnce(url, host, ip, identityHash, identityPub, ed25519PrivateKey)
                return
            } catch (e: Exception) {
                lastError = e
                AppLogger.warn(TAG, "control channel failed via $ip: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Relay control channel failed")
    }

    @Suppress("LongParameterList") // url/host/ip plus the three identity parts of the listener hello
    private suspend fun connectControlChannelOnce(
        url: String,
        host: String?,
        targetIp: String?,
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKey: ByteArray,
    ) {
        val hello = relayHelloFactory.buildListenerHello(identityHash, identityPub, ed25519PrivateKey)
        val request = Request.Builder().url(url).build()
        val openLatch = CompletableDeferred<Unit>()
        val closeLatch = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
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
                    RelayEventType.RELAY_EVENT_TYPE_ERROR -> {
                        AppLogger.warn(TAG, "relay error: ${event.message}")
                        webSocket.close(1000, event.message)
                    }
                    else -> Unit
                }
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
        val webSocket = when {
            host != null && targetIp != null ->
                WebSocketFrameClient.httpClientWithPinning(host, targetIp).newWebSocket(request, listener)
            host != null ->
                WebSocketFrameClient.httpClientWithPinning(host).newWebSocket(request, listener)
            else ->
                WebSocketFrameClient.httpClient().newWebSocket(request, listener)
        }
        try {
            openLatch.await()
            val keepAlive = scope.launch { keepAliveLoop(webSocket, closeLatch) }
            closeLatch.await()
            keepAlive.cancel()
        } finally {
            webSocket.cancel()
        }
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
        }
    }

    /**
     * Accepts one relayed circuit and hands it to the inbound handler. Nothing
     * here may escape: a failing relay dial or a throwing handler is logged and
     * the circuit dropped, never the listener (or the process).
     */
    internal suspend fun acceptCircuit(url: String, circuitId: String): Boolean {
        val inbound = handler ?: return false
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

    companion object {
        private const val TAG = "Relay"
        private const val KEEPALIVE_INTERVAL_MS = 40_000L
        private val KEEPALIVE_FRAME = byteArrayOf(0)

        private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(TAG))
    }
}
