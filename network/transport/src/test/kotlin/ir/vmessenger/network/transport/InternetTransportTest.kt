package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.LengthPrefixedFrames
import ir.vmessenger.core.common.network.TransportIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** JVM loopback tests for the cold, cancellable TCP listener. */
class InternetTransportTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun noAcceptBeforeCollect() {
        val port = freePort()
        val transport = InternetTransport()
        // Creating the flow must not bind: the port is still free for someone else.
        transport.listen(port)
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            probe.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            assertTrue(probe.isBound)
        }
    }

    @Test
    fun bindFailureReachesCollector() = runBlocking {
        val port = freePort()
        ServerSocket().use { occupant ->
            // reuseAddress on both sides would let the bind succeed on some
            // platforms, so the occupant claims the port exclusively.
            occupant.reuseAddress = false
            occupant.bind(InetSocketAddress(port))
            val transport = InternetTransport()
            val failure = runCatching {
                withTimeout(5_000) { transport.listen(port).first() }
            }.exceptionOrNull()
            assertTrue("expected a bind failure, got $failure", failure is BindException)
        }
    }

    @Test
    fun acceptedConnectionRoundTripsAndCancelReleasesPort() = runBlocking {
        val port = freePort()
        val transport = InternetTransport()
        val accepted = CompletableDeferred<Connection>()
        val listener = launch(Dispatchers.IO) {
            transport.listen(port).collect { accepted.complete(it) }
        }
        val client = awaitConnect(port)
        val server = withTimeout(5_000) { accepted.await() }
        LengthPrefixedFrames.writeFrame(client.getOutputStream(), "ping".toByteArray())
        val received = withTimeout(5_000) { server.read().first() }
        assertArrayEquals("ping".toByteArray(), received)
        assertTrue(server.write("pong".toByteArray()).isSuccess)
        assertArrayEquals("pong".toByteArray(), LengthPrefixedFrames.readFrame(client.getInputStream()))
        client.close()
        listener.cancel()
        listener.join()
        // The server socket is closed with the collector: the port is free again.
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            probe.bind(InetSocketAddress(port))
        }
        server.close()
    }

    @Test
    fun idleSocketTimesOut() = runBlocking {
        val port = freePort()
        val transport = InternetTransport(idleTimeoutMs = 300)
        val accepted = CompletableDeferred<Connection>()
        val listener = launch(Dispatchers.IO) {
            transport.listen(port).collect { accepted.complete(it) }
        }
        val client = awaitConnect(port)
        val server = withTimeout(5_000) { accepted.await() }
        assertEquals(ConnectionState.OPEN, server.state.value)
        // The client never writes: the accepted socket must give up on its own.
        val terminal = withTimeout(5_000) { server.state.first { it != ConnectionState.OPEN } }
        assertNotEquals(ConnectionState.OPEN, terminal)
        assertEquals(ConnectionState.FAILED, terminal)
        client.close()
        listener.cancel()
    }

    @Test
    fun connectSetsRemoteEndpoint() = runBlocking {
        val port = freePort()
        val transport = InternetTransport()
        val listener = launch(Dispatchers.IO) { transport.listen(port).collect { it.close() } }
        // Give the listener a moment to bind before dialing.
        awaitConnect(port).close()
        val endpoint = Endpoint(TransportIds.INTERNET, "127.0.0.1:$port")
        val connection = transport.connect(endpoint).getOrThrow()
        assertEquals(endpoint, connection.remote)
        connection.close()
        listener.cancel()
    }

    @Test
    fun stalledWriteIsCancellableAndFailsConnection() = runBlocking {
        // A peer that accepts but never reads: once the socket buffers fill, the
        // blocking write must be abandoned by the caller's timeout, not TCP's.
        ServerSocket(0).use { sink ->
            val endpoint = Endpoint(TransportIds.INTERNET, "127.0.0.1:${sink.localPort}")
            val transport = InternetTransport()
            val connection = transport.connect(endpoint).getOrThrow()
            val peer = sink.accept()
            val frame = ByteArray(LengthPrefixedFrames.MAX_FRAME_SIZE)
            val outcome = runCatching {
                withTimeout(1_500) {
                    repeat(1_000) { connection.write(frame).getOrThrow() }
                }
            }
            assertTrue("expected the write to time out, got $outcome", outcome.isFailure)
            assertEquals(ConnectionState.FAILED, connection.state.value)
            assertTrue(connection.write(byteArrayOf(1)).isFailure)
            peer.close()
        }
    }

    private fun awaitConnect(port: Int): Socket {
        var last: Exception? = null
        repeat(50) {
            try {
                return Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500) }
            } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw checkNotNull(last)
    }
}
