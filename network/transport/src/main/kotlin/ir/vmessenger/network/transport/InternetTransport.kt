package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.TransportId
import ir.vmessenger.core.common.network.TransportIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InternetTransport @Inject constructor() : Transport {
    override val id: TransportId = TransportIds.INTERNET
    override val capabilities = TransportCapabilities(reliable = true, ordered = true, mtu = 65_535)

    override fun canReach(endpoint: Endpoint): Boolean =
        endpoint.transport == TransportIds.INTERNET && endpoint.address.contains(':')

    override suspend fun connect(endpoint: Endpoint): Result<Connection> = withContext(Dispatchers.IO) {
        runCatching {
            val (host, port) = endpoint.address.splitHostPort()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            InternetConnection(endpoint, socket)
        }
    }

    fun listenOnPort(port: Int): Flow<Connection> {
        val flow = MutableSharedFlow<Connection>(extraBufferCapacity = 16)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            ServerSocket(port).use { server ->
                while (true) {
                    val socket = server.accept()
                    val remote = Endpoint(
                        transport = TransportIds.INTERNET,
                        address = socket.remoteSocketAddress.toString().removePrefix("/"),
                    )
                    flow.emit(InternetConnection(remote, socket))
                }
            }
        }
        return flow.asSharedFlow()
    }

    override fun listen(port: Int): Flow<Connection> = listenOnPort(port)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}

private class InternetConnection(
    override val remote: Endpoint,
    private val socket: Socket,
) : Connection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val _state = MutableStateFlow(ConnectionState.OPEN)
    override val state: StateFlow<ConnectionState> = _state
    private val _reads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)

    init {
        scope.launch {
            try {
                while (_state.value == ConnectionState.OPEN) {
                    val frame = LengthPrefixedFrames.readFrame(input) ?: break
                    _reads.emit(frame)
                }
            } catch (_: Exception) {
                _state.value = ConnectionState.FAILED
            } finally {
                closeInternal()
            }
        }
    }

    override suspend fun write(frame: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(_state.value == ConnectionState.OPEN) { "Connection closed" }
            LengthPrefixedFrames.writeFrame(output, frame)
        }
    }

    override fun read(): Flow<ByteArray> = _reads

    override suspend fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        if (_state.value == ConnectionState.CLOSED) return
        _state.value = ConnectionState.CLOSED
        runCatching { socket.close() }
        scope.cancel()
    }
}

private fun String.splitHostPort(): Pair<String, Int> {
    val idx = lastIndexOf(':')
    require(idx > 0) { "Invalid address: $this" }
    return substring(0, idx) to substring(idx + 1).toInt()
}
