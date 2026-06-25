package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.common.network.WebSocketFrameClient
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class RelayTransport @Inject constructor() : Transport {
    override val id = TransportIds.RELAY
    override val capabilities = TransportCapabilities(reliable = true, ordered = true, mtu = 65_535)

    override fun canReach(endpoint: Endpoint): Boolean =
        endpoint.transport == TransportIds.RELAY &&
            (endpoint.address.startsWith("ws://") || endpoint.address.startsWith("wss://"))

    override suspend fun connect(endpoint: Endpoint): Result<Connection> =
        Result.failure(IllegalStateException("Use connect(endpoint, relayTargetId) for RELAY transport"))

    suspend fun connect(endpoint: Endpoint, relayTargetId: ByteArray): Result<Connection> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = endpoint.address.ifBlank { NetworkConfig.DEFAULT_RELAY_URL }
                require(relayTargetId.size == 32) { "relayTargetId must be 32 bytes" }
                val circuitId = UUID.randomUUID().toString()
                val hello = RelayHello.newBuilder()
                    .setRole(RelayRole.RELAY_ROLE_DIALER)
                    .setTargetId(com.google.protobuf.ByteString.copyFrom(relayTargetId))
                    .setCircuitId(circuitId)
                    .setTs(System.currentTimeMillis())
                    .build()
                openRelayCircuit(url, hello, awaitReady = true)
            }
        }

    suspend fun openRelayCircuit(
        url: String,
        hello: RelayHello,
        awaitReady: Boolean,
    ): RelayConnection = suspendCancellableCoroutine { cont ->
        val remote = Endpoint(TransportIds.RELAY, url)
        val connectionRef = arrayOf<RelayConnection?>(null)
        val request = Request.Builder().url(url).build()
        val socketHolder = arrayOfNulls<WebSocket>(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(hello.toByteArray().toByteString())
                if (!awaitReady) {
                    val connection = RelayConnection(remote, webSocket, dataMode = true)
                    connectionRef[0] = connection
                    if (cont.isActive) cont.resume(connection)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val existing = connectionRef[0]
                if (existing != null) {
                    existing.dispatchMessage(bytes)
                    return
                }
                val payload = bytes.toByteArray()
                if (awaitReady) {
                    val event = RelayEvent.parseFrom(payload)
                    when (event.type) {
                        RelayEventType.RELAY_EVENT_TYPE_READY -> {
                            val connection = RelayConnection(remote, webSocket, dataMode = true)
                            connectionRef[0] = connection
                            if (cont.isActive) cont.resume(connection)
                        }
                        RelayEventType.RELAY_EVENT_TYPE_INCOMING -> {
                            // control channel only
                        }
                        RelayEventType.RELAY_EVENT_TYPE_ERROR -> {
                            webSocket.close(1000, event.message)
                            if (cont.isActive) {
                                cont.resumeWithException(IllegalStateException(event.message))
                            }
                        }
                        else -> Unit
                    }
                } else {
                    val connection = RelayConnection(remote, webSocket, dataMode = false)
                    connectionRef[0] = connection
                    connection.dispatchMessage(bytes)
                    if (cont.isActive) cont.resume(connection)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionRef[0]?.markClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionRef[0]?.markFailed()
                if (cont.isActive && connectionRef[0] == null) {
                    cont.resumeWithException(t)
                }
            }
        }
        socketHolder[0] = WebSocketFrameClient.httpClient().newWebSocket(request, listener)
        cont.invokeOnCancellation {
            socketHolder[0]?.close(1000, "cancelled")
        }
    }

    override fun listen(port: Int): Flow<Connection> =
        throw UnsupportedOperationException("Relay inbound uses RelayListener")
}

class RelayConnection(
    override val remote: Endpoint,
    private val webSocket: WebSocket,
    dataMode: Boolean,
) : Connection {
    private val _state = MutableStateFlow(ConnectionState.OPEN)
    override val state: StateFlow<ConnectionState> = _state
    private val _reads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var acceptsData = dataMode

    internal fun dispatchMessage(bytes: ByteString) {
        if (!acceptsData) {
            val event = runCatching { RelayEvent.parseFrom(bytes.toByteArray()) }.getOrNull()
            when (event?.type) {
                RelayEventType.RELAY_EVENT_TYPE_READY -> acceptsData = true
                RelayEventType.RELAY_EVENT_TYPE_INCOMING -> Unit
                RelayEventType.RELAY_EVENT_TYPE_ERROR -> _state.value = ConnectionState.FAILED
                else -> if (acceptsData) _reads.tryEmit(bytes.toByteArray())
            }
            return
        }
        _reads.tryEmit(bytes.toByteArray())
    }

    internal fun markClosed() {
        _state.value = ConnectionState.CLOSED
    }

    internal fun markFailed() {
        _state.value = ConnectionState.FAILED
    }

    override suspend fun write(frame: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(_state.value == ConnectionState.OPEN) { "Connection closed" }
            val sent = webSocket.send(frame.toByteString())
            check(sent) { "WebSocket send failed" }
        }
    }

    override fun read(): Flow<ByteArray> = _reads

    override suspend fun close() {
        if (_state.value == ConnectionState.CLOSED) return
        _state.value = ConnectionState.CLOSED
        webSocket.close(1000, "closed")
    }
}
