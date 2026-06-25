package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkConfig
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
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handler: InboundConnectionHandler? = null

    @Volatile
    private var identityHash: ByteArray? = null

    @Volatile
    private var identityPub: ByteArray? = null

    @Volatile
    private var ed25519PrivateKey: ByteArray? = null

    @Volatile
    private var running = false

    fun configure(
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKey: ByteArray,
        inboundHandler: InboundConnectionHandler,
    ) {
        this.identityHash = identityHash
        this.identityPub = identityPub
        this.ed25519PrivateKey = ed25519PrivateKey
        this.handler = inboundHandler
    }

    fun start() {
        if (running) return
        running = true
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        AppLogger.info("Relay", "listener starting on ${NetworkConfig.relayAddress}")
        scope.launch { maintainControlChannel() }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun maintainControlChannel() {
        var backoffMs = 1_000L
        while (running && scope.isActive) {
            val hash = identityHash
            val pub = identityPub
            val key = ed25519PrivateKey
            if (hash == null || pub == null || key == null) {
                delay(1_000)
                continue
            }
            try {
                connectControlChannel(hash, pub, key)
                backoffMs = 1_000L
                AppLogger.info("Relay", "control channel ended, reconnecting in ${backoffMs}ms")
                delay(backoffMs)
            } catch (e: Exception) {
                AppLogger.warn("Relay", "control channel lost: ${e.message}, retry in ${backoffMs}ms")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun connectControlChannel(
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKey: ByteArray,
    ) {
        val url = NetworkConfig.relayAddress
        val hello = relayHelloFactory.buildListenerHello(identityHash, identityPub, ed25519PrivateKey)
        val request = Request.Builder().url(url).build()
        val openLatch = CompletableDeferred<Unit>()
        val closeLatch = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(hello.toByteArray().toByteString())
                AppLogger.info("Relay", "control channel connected")
                openLatch.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val event = runCatching { RelayEvent.parseFrom(bytes.toByteArray()) }.getOrNull() ?: return
                when (event.type) {
                    RelayEventType.RELAY_EVENT_TYPE_INCOMING -> {
                        AppLogger.info("Relay", "incoming circuit ${event.circuitId}")
                        scope.launch { acceptCircuit(event.circuitId) }
                    }
                    RelayEventType.RELAY_EVENT_TYPE_ERROR -> {
                        AppLogger.warn("Relay", "relay error: ${event.message}")
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
        val webSocket = WebSocketFrameClient.httpClient().newWebSocket(request, listener)
        try {
            openLatch.await()
            closeLatch.await()
        } finally {
            webSocket.cancel()
        }
    }

    private suspend fun acceptCircuit(circuitId: String) {
        val inbound = handler ?: return
        val url = NetworkConfig.relayAddress
        val hello = relayHelloFactory.buildAcceptHello(circuitId)
        val connection = relayTransport.openRelayCircuit(url, hello, awaitReady = true)
        inbound.onInboundConnection(connection)
    }
}
