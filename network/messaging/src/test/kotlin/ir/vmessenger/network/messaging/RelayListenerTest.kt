package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.RelaySource
import ir.vmessenger.core.common.network.SelectedRelay
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import ir.vmessenger.network.transport.RelayConnection
import ir.vmessenger.network.transport.RelayTransport
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RelayListener.acceptCircuit] must never let a failure escape to the listener scope. */
class RelayListenerTest {
    private val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))

    private class FakeWebSocket : WebSocket {
        var closed = false
        override fun request(): Request = Request.Builder().url("wss://relay.invalid/relay").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closed = true
            return true
        }

        override fun cancel() = Unit
    }

    private class ThrowingRelayTransport : RelayTransport() {
        var attempts = 0
        override suspend fun openRelayCircuit(url: String, hello: RelayHello, awaitReady: Boolean): RelayConnection {
            attempts++
            error("relay unreachable")
        }
    }

    /** Hands out a circuit over [socket] and remembers the ACCEPT hello it was given. */
    private class AcceptingRelayTransport(private val socket: WebSocket) : RelayTransport() {
        var lastHello: RelayHello? = null
        override suspend fun openRelayCircuit(url: String, hello: RelayHello, awaitReady: Boolean): RelayConnection {
            lastHello = hello
            return RelayConnection(Endpoint(TransportIds.RELAY, url), socket, dataMode = true)
        }
    }

    private class FakeRelayDirectory : RelayDirectory {
        override suspend fun activeRelay() = SelectedRelay("wss://relay.invalid/relay", RelaySource.DEFAULT)
        override fun lastSelectedRelay(): SelectedRelay? = null
        override suspend fun reportResult(url: String, ok: Boolean) = Unit
    }

    @Test
    fun acceptCircuitFailureDoesNotCrash() = runBlocking {
        val transport = ThrowingRelayTransport()
        val listener = RelayListener(transport, RelayHelloFactory(crypto), FakeRelayDirectory())
        listener.configure(ByteArray(32), ByteArray(32), { ByteArray(64) }) { error("handler must not run") }

        val accepted = runCatching { listener.acceptCircuit("wss://relay.invalid/relay", "circuit-1") }

        assertTrue("acceptCircuit must swallow the dial failure: ${accepted.exceptionOrNull()}", accepted.isSuccess)
        assertFalse(accepted.getOrThrow())
        assertEquals(1, transport.attempts)
    }

    @Test
    fun handlerFailureClosesCircuitAndIsSwallowed() = runBlocking {
        val socket = FakeWebSocket()
        val transport = AcceptingRelayTransport(socket)
        val listener = RelayListener(transport, RelayHelloFactory(crypto), FakeRelayDirectory())
        listener.configure(ByteArray(32), ByteArray(32), { ByteArray(64) }) { error("handshake exploded") }

        val accepted = runCatching { listener.acceptCircuit("wss://relay.invalid/relay", "circuit-2") }

        assertTrue(accepted.isSuccess)
        assertFalse(accepted.getOrThrow())
        assertTrue("circuit must be closed when the handler fails", socket.closed)
        assertEquals(RelayRole.RELAY_ROLE_ACCEPT, transport.lastHello?.role)
        assertEquals("circuit-2", transport.lastHello?.circuitId)
    }

    @Test
    fun acceptWithoutHandlerIsNoOp() = runBlocking {
        val transport = ThrowingRelayTransport()
        val listener = RelayListener(transport, RelayHelloFactory(crypto), FakeRelayDirectory())
        assertFalse(listener.acceptCircuit("wss://relay.invalid/relay", "circuit-3"))
        assertEquals("no dial without a handler", 0, transport.attempts)
    }
}
