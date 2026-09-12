package ir.vmessenger.network.transport

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.TransportId
import ir.vmessenger.core.common.network.TransportIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct TCP transport. [listen] is cold: nothing binds until the flow is
 * collected, bind/accept failures reach the collector (which owns the retry
 * policy), and cancelling the collector closes the server socket. Accepted
 * sockets get an idle read timeout so a silent peer cannot pin a reader forever.
 */
@Singleton
class InternetTransport(private val idleTimeoutMs: Int) : Transport {
    @Inject
    constructor() : this(IDLE_TIMEOUT_MS)

    override val id: TransportId = TransportIds.INTERNET
    override val capabilities = TransportCapabilities(reliable = true, ordered = true, mtu = 65_535)

    override fun canReach(endpoint: Endpoint): Boolean =
        endpoint.transport == TransportIds.INTERNET && endpoint.address.contains(':')

    override suspend fun connect(endpoint: Endpoint): Result<Connection> = withContext(Dispatchers.IO) {
        runCatching {
            val (host, port) = endpoint.address.splitHostPort()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // Same idle read timeout as accepted sockets: a peer that goes
            // silent ends our reader instead of pinning it until TCP gives up.
            socket.soTimeout = idleTimeoutMs
            socket.tcpNoDelay = true
            InternetConnection(endpoint, socket)
        }
    }

    override fun listen(port: Int): Flow<Connection> = channelFlow {
        val server = withContext(Dispatchers.IO) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }
        launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val socket = server.accept()
                    socket.soTimeout = idleTimeoutMs
                    socket.tcpNoDelay = true
                    val remote = Endpoint(
                        transport = TransportIds.INTERNET,
                        address = socket.remoteSocketAddress.toString().removePrefix("/"),
                    )
                    send(InternetConnection(remote, socket))
                }
            } catch (e: IOException) {
                // A closed server socket (collector cancelled) is the normal end;
                // anything else is a real accept failure the collector must see.
                if (!server.isClosed) throw e
            }
        }
        awaitClose { runCatching { server.close() } }
    }.buffer(ACCEPT_BUFFER)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val ACCEPT_BUFFER = 16
    }
}

private class InternetConnection(
    override val remote: Endpoint,
    private val socket: Socket,
) : Connection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler("Transport"))
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val _state = MutableStateFlow(ConnectionState.OPEN)
    override val state: StateFlow<ConnectionState> = _state
    private val closed = AtomicBoolean(false)

    // A bounded channel (not a replay=0 SharedFlow) buffers frames the socket
    // reader produces before any consumer subscribes — the first handshake
    // frame must never be dropped for want of a collector — and once it fills
    // the reader suspends, so a slow consumer pushes back on TCP instead of
    // growing the heap.
    private val reads = Channel<ByteArray>(READ_BUFFER)

    init {
        scope.launch {
            var outcome = ConnectionState.CLOSED
            try {
                while (_state.value == ConnectionState.OPEN) {
                    val frame = LengthPrefixedFrames.readFrame(input) ?: break
                    reads.send(frame)
                }
            } catch (_: IOException) {
                outcome = ConnectionState.FAILED
            } catch (_: IllegalArgumentException) {
                outcome = ConnectionState.FAILED
            } finally {
                reads.close()
                closeInternal(outcome)
            }
        }
    }

    /**
     * The blocking socket write runs on the connection's own scope so a caller
     * that gives up (a write timeout) can close the socket — the only thing
     * that unblocks a kernel write to a peer that stopped reading — instead of
     * being pinned until TCP's own retransmit timeout minutes later.
     */
    override suspend fun write(frame: ByteArray): Result<Unit> {
        if (_state.value != ConnectionState.OPEN) return Result.failure(IOException("Connection closed"))
        val pending = scope.async { LengthPrefixedFrames.writeFrame(output, frame) }
        val outcome = runCatching { pending.await() }
        if (outcome.exceptionOrNull() is CancellationException) {
            closeInternal(ConnectionState.FAILED)
            // Rethrows when the caller itself was cancelled (timeout); a
            // connection closed underneath us is an ordinary write failure.
            currentCoroutineContext().ensureActive()
        }
        return outcome
    }

    override fun read(): Flow<ByteArray> = reads.receiveAsFlow()

    override suspend fun close() {
        closeInternal(ConnectionState.CLOSED)
    }

    private fun closeInternal(finalState: ConnectionState) {
        if (!closed.compareAndSet(false, true)) return
        _state.value = finalState
        runCatching { socket.close() }
        scope.cancel()
    }

    private companion object {
        const val READ_BUFFER = 64
    }
}

private fun String.splitHostPort(): Pair<String, Int> {
    val idx = lastIndexOf(':')
    require(idx > 0) { "Invalid address: $this" }
    return substring(0, idx) to substring(idx + 1).toInt()
}
