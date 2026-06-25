package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.WebSocketFrameClient
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.RelayTransport
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        scope.launch { maintainControlChannel() }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
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
            } catch (_: Exception) {
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
        val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
        val socketHolder = arrayOfNulls<WebSocket>(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ByteString.of(hello.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val event = runCatching { RelayEvent.parseFrom(bytes.toByteArray()) }.getOrNull() ?: return
                when (event.type) {
                    RelayEventType.RELAY_EVENT_TYPE_INCOMING -> {
                        scope.launch { acceptCircuit(event.circuitId) }
                    }
                    RelayEventType.RELAY_EVENT_TYPE_ERROR -> {
                        webSocket.close(1000, event.message)
                    }
                    else -> Unit
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!latch.isCompleted) latch.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!latch.isCompleted) latch.completeExceptionally(t)
            }
        }
        socketHolder[0] = WebSocketFrameClient.httpClient().newWebSocket(request, listener)
        latch.await()
    }

    private suspend fun acceptCircuit(circuitId: String) {
        val inbound = handler ?: return
        val url = NetworkConfig.relayAddress
        val hello = relayHelloFactory.buildAcceptHello(circuitId)
        val connection = relayTransport.openRelayCircuit(url, hello, awaitReady = true)
        inbound.onInboundConnection(connection)
    }
}
