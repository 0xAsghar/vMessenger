package ir.vmessenger.network.transport

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.common.network.TransportId
import ir.vmessenger.core.common.network.TransportIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UDP transport for NAT traversal attempts (docs/P2P-Phases.md Phase 7).
 *
 * Uses the same length-prefixed framing as TCP. When [P2PConfig.natTraversalEnabled]
 * is off, [canReach] returns false so the transport is skipped.
 */
@Singleton
class UdpTransport @Inject constructor() : Transport {
    override val id: TransportId = TransportIds.UDP
    override val capabilities = TransportCapabilities(reliable = false, ordered = false, mtu = 65_535)

    override fun canReach(endpoint: Endpoint): Boolean =
        P2PConfig.natTraversalEnabled &&
            endpoint.transport == TransportIds.UDP &&
            endpoint.address.contains(':')

    override suspend fun connect(endpoint: Endpoint): Result<Connection> = withContext(Dispatchers.IO) {
        runCatching {
            val (host, port) = endpoint.address.splitHostPort()
            val socket = DatagramSocket()
            socket.connect(InetSocketAddress(host, port))
            UdpConnection(endpoint, socket)
        }
    }

    override fun listen(port: Int): kotlinx.coroutines.flow.Flow<Connection> =
        kotlinx.coroutines.flow.emptyFlow()
}

private class UdpConnection(
    override val remote: Endpoint,
    private val socket: DatagramSocket,
) : Connection {
    private val closed = AtomicBoolean(false)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.OPEN)
    override val state: kotlinx.coroutines.flow.StateFlow<ConnectionState> = _state

    override suspend fun write(frame: ByteArray): Result<Unit> = runCatching {
        val out = java.io.ByteArrayOutputStream()
        LengthPrefixedFrames.writeFrame(out, frame)
        val bytes = out.toByteArray()
        val packet = DatagramPacket(bytes, bytes.size)
        socket.send(packet)
    }

    override fun read(): kotlinx.coroutines.flow.Flow<ByteArray> =
        kotlinx.coroutines.flow.flow {
            val buffer = ByteArray(65_536)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.receive(packet)
            val frame = LengthPrefixedFrames.readFrame(java.io.ByteArrayInputStream(packet.data, 0, packet.length))
            if (frame != null) emit(frame)
        }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            _state.value = ConnectionState.CLOSED
            socket.close()
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 10_000
    }
}

private fun String.splitHostPort(): Pair<String, Int> {
    val idx = lastIndexOf(':')
    require(idx > 0) { "Invalid address: $this" }
    return substring(0, idx) to substring(idx + 1).toInt()
}
