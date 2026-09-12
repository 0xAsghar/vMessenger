package ir.vmessenger.network.transport

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NodeAddressPolicy
import ir.vmessenger.core.common.network.RelayDns
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.common.network.WebSocketFrameClient
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
open class RelayTransport @Inject constructor() : Transport {
    override val id = TransportIds.RELAY
    override val capabilities = TransportCapabilities(reliable = true, ordered = true, mtu = 65_535)

    private companion object {
        const val DIAL_TIMEOUT_MS = 15_000L
    }

    /** Relay endpoints must satisfy [NodeAddressPolicy] (release: `wss://` with a host only). */
    override fun canReach(endpoint: Endpoint): Boolean =
        endpoint.transport == TransportIds.RELAY && NodeAddressPolicy.current.isRelayAllowed(endpoint.address)

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

    open suspend fun openRelayCircuit(
        url: String,
        hello: RelayHello,
        awaitReady: Boolean,
    ): RelayConnection {
        val host = RelayDns.hostFromUrl(url)
        val ips = host?.let { RelayDns.candidateIps(it) }.orEmpty()
        if (host == null || ips.isEmpty()) {
            return dialWithTimeout(url, hello, awaitReady, host, targetIp = null)
        }
        var lastError: Exception? = null
        for (ip in ips) {
            try {
                return dialWithTimeout(url, hello, awaitReady, host, ip)
            } catch (e: Exception) {
                lastError = e
                if (!shouldRetryRelayDial(e)) throw e
                AppLogger.info("Relay", "dial retry next backend ip for $host after: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Relay connect failed")
    }

    /**
     * A relay that accepts the socket but never returns READY (or ERROR) would
     * otherwise leave the dial suspended forever — OkHttp pings keep the socket
     * alive, so no timeout fires. Because this runs while holding the messaging
     * send mutex, a single hung dial freezes all outbound delivery after a
     * network/IP change. Bound each attempt so the mutex is always released.
     */
    private suspend fun dialWithTimeout(
        url: String,
        hello: RelayHello,
        awaitReady: Boolean,
        host: String?,
        targetIp: String?,
    ): RelayConnection =
        withTimeoutOrNull(DIAL_TIMEOUT_MS) {
            openRelayCircuitOnce(url, hello, awaitReady, host, targetIp)
        } ?: throw java.net.SocketTimeoutException("Relay dial timed out after ${DIAL_TIMEOUT_MS}ms")

    private suspend fun openRelayCircuitOnce(
        url: String,
        hello: RelayHello,
        awaitReady: Boolean,
        host: String?,
        targetIp: String?,
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
                    val event = runCatching { RelayEvent.parseFrom(payload) }.getOrElse { parseError ->
                        webSocket.close(1002, "malformed relay event")
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("malformed relay event", parseError))
                        }
                        return
                    }
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
        socketHolder[0] = when {
            host != null && targetIp != null ->
                WebSocketFrameClient.httpClientWithPinning(host, targetIp).newWebSocket(request, listener)
            host != null ->
                WebSocketFrameClient.httpClientWithPinning(host).newWebSocket(request, listener)
            else ->
                WebSocketFrameClient.httpClient().newWebSocket(request, listener)
        }
        cont.invokeOnCancellation {
            socketHolder[0]?.close(1000, "cancelled")
        }
    }

    private fun shouldRetryRelayDial(error: Exception): Boolean {
        if (RelayDns.isPeerNotListening(error.message)) return true
        val message = error.message.orEmpty()
        return message.contains("failed to connect", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException
    }

    override fun listen(port: Int): Flow<Connection> =
        throw UnsupportedOperationException("Relay inbound uses RelayListener")
}

/**
 * One relay circuit over a WebSocket. Inbound frames are buffered in a bounded
 * channel: the relay pushes faster than a stalled consumer drains, and rather
 * than growing the heap or silently dropping ciphertext (which would desync the
 * ratchet anyway) an overflow fails the connection so the peer re-handshakes.
 */
class RelayConnection(
    override val remote: Endpoint,
    private val webSocket: WebSocket,
    dataMode: Boolean,
) : Connection {
    private val _state = MutableStateFlow(ConnectionState.OPEN)
    override val state: StateFlow<ConnectionState> = _state
    private val incoming = Channel<ByteArray>(INCOMING_BUFFER)
    private var acceptsData = dataMode

    internal fun dispatchMessage(bytes: ByteString) {
        if (!acceptsData) {
            val event = runCatching { RelayEvent.parseFrom(bytes.toByteArray()) }.getOrNull()
            when (event?.type) {
                RelayEventType.RELAY_EVENT_TYPE_READY -> acceptsData = true
                RelayEventType.RELAY_EVENT_TYPE_INCOMING -> Unit
                RelayEventType.RELAY_EVENT_TYPE_ERROR -> markFailed()
                else -> enqueueFrame(bytes)
            }
            return
        }
        enqueueFrame(bytes)
    }

    private fun enqueueFrame(bytes: ByteString) {
        if (_state.value != ConnectionState.OPEN) return
        val result = incoming.trySend(bytes.toByteArray())
        if (result.isSuccess) return
        AppLogger.warn("Relay", "inbound buffer overflow ($INCOMING_BUFFER frames); failing connection")
        markFailed()
        webSocket.close(1008, "receiver too slow")
    }

    internal fun markClosed() {
        if (_state.value == ConnectionState.OPEN) _state.value = ConnectionState.CLOSED
        incoming.close()
    }

    internal fun markFailed() {
        if (_state.value == ConnectionState.OPEN) _state.value = ConnectionState.FAILED
        incoming.close()
    }

    /**
     * OkHttp queues outbound frames in memory and, once the queue passes 16 MiB,
     * `send` returns false and closes the socket. A batch streamed faster than
     * the relay uplink (any large attachment) would hit that at the same index
     * on every retry, so writes wait for the queue to drain below
     * [WRITE_HIGH_WATER] first, bounded by [WRITE_TIMEOUT_MS]; a refused send
     * fails the connection so the caller re-handshakes instead of retrying it.
     */
    override suspend fun write(frame: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            awaitQueueRoom(frame.size)
            check(_state.value == ConnectionState.OPEN) { "Connection closed" }
            val sent = webSocket.send(frame.toByteString())
            if (!sent) {
                markFailed()
                error("WebSocket send refused")
            }
        }
    }

    private suspend fun awaitQueueRoom(frameSize: Int) {
        val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS
        while (webSocket.queueSize() + frameSize > WRITE_HIGH_WATER) {
            check(_state.value == ConnectionState.OPEN) { "Connection closed" }
            if (System.currentTimeMillis() >= deadline) {
                markFailed()
                webSocket.close(1001, "send queue stalled")
                error("WebSocket send queue stalled for ${WRITE_TIMEOUT_MS}ms")
            }
            delay(QUEUE_POLL_MS)
        }
    }

    override fun read() = incoming.receiveAsFlow()

    override suspend fun close() {
        if (_state.value != ConnectionState.OPEN) return
        _state.value = ConnectionState.CLOSED
        incoming.close()
        webSocket.close(1000, "closed")
    }

    companion object {
        const val INCOMING_BUFFER = 256

        /** Outbound bytes OkHttp may hold before a write waits for the uplink. */
        const val WRITE_HIGH_WATER = 1L * 1024 * 1024
        const val WRITE_TIMEOUT_MS = 30_000L
        private const val QUEUE_POLL_MS = 10L
    }
}
