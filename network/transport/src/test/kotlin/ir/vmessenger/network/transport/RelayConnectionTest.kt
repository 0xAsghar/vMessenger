package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConnectionTest {
    private class FakeWebSocket : WebSocket {
        var closeCode: Int? = null
        val sent = mutableListOf<ByteString>()

        @Volatile
        var queued: Long = 0

        @Volatile
        var accepts = true

        override fun request(): Request = Request.Builder().url("wss://relay.invalid/relay").build()
        override fun queueSize(): Long = queued
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = accepts && sent.add(bytes)
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }

        override fun cancel() = Unit
    }

    private companion object {
        val RELAY_ENDPOINT = Endpoint(TransportIds.RELAY, "wss://relay.invalid/relay")
    }

    @Test
    fun overflowFailsConnection() = runBlocking {
        val socket = FakeWebSocket()
        val connection = RelayConnection(RELAY_ENDPOINT, socket, dataMode = true)
        repeat(RelayConnection.INCOMING_BUFFER) { i ->
            connection.dispatchMessage(byteArrayOf(i.toByte()).toByteString())
        }
        assertEquals(ConnectionState.OPEN, connection.state.value)
        assertEquals(null, socket.closeCode)

        connection.dispatchMessage(byteArrayOf(-1).toByteString())

        assertEquals(ConnectionState.FAILED, connection.state.value)
        assertTrue("socket must be closed on overflow", socket.closeCode != null)
        // Buffered frames are still drained, the overflowing one is gone, and the
        // channel is closed so the reader terminates instead of waiting forever.
        val drained = connection.read().toList()
        assertEquals(RelayConnection.INCOMING_BUFFER, drained.size)
        assertFalse(connection.write(byteArrayOf(1)).isSuccess)
    }

    @Test
    fun writeWaitsForSendQueueToDrain() = runBlocking {
        val socket = FakeWebSocket().apply { queued = RelayConnection.WRITE_HIGH_WATER }
        val connection = RelayConnection(RELAY_ENDPOINT, socket, dataMode = true)
        val write = async(Dispatchers.Default) { connection.write(byteArrayOf(1, 2, 3)) }
        delay(100)
        assertTrue("write must wait while the queue is above the high-water mark", write.isActive)
        assertTrue(socket.sent.isEmpty())

        socket.queued = 0

        assertTrue(withTimeout(2_000) { write.await() }.isSuccess)
        assertEquals(1, socket.sent.size)
        assertEquals(ConnectionState.OPEN, connection.state.value)
    }

    @Test
    fun refusedSendFailsConnection() = runBlocking {
        val socket = FakeWebSocket().apply { accepts = false }
        val connection = RelayConnection(RELAY_ENDPOINT, socket, dataMode = true)

        assertFalse(connection.write(byteArrayOf(1)).isSuccess)

        assertEquals(ConnectionState.FAILED, connection.state.value)
        assertTrue(connection.read().toList().isEmpty())
    }

    @Test
    fun framesAfterFailureAreIgnored() = runBlocking {
        val socket = FakeWebSocket()
        val connection = RelayConnection(RELAY_ENDPOINT, socket, dataMode = true)
        connection.markFailed()
        connection.dispatchMessage(byteArrayOf(7).toByteString())
        assertEquals(ConnectionState.FAILED, connection.state.value)
        assertTrue(connection.read().toList().isEmpty())
    }
}
