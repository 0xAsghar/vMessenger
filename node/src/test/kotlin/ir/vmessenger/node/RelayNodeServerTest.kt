package ir.vmessenger.node

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import ir.vmessenger.core.common.network.RelayProof
import ir.vmessenger.core.proto.relay.v1.RelayEvent
import ir.vmessenger.core.proto.relay.v1.RelayEventType
import ir.vmessenger.core.proto.relay.v1.RelayHello
import ir.vmessenger.core.proto.relay.v1.RelayRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class RelayNodeServerTest {
    private val sodium = LazySodiumJava(SodiumJava())

    private class Identity(val pub: ByteArray, val secret: ByteArray) {
        val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(pub)
    }

    private fun identity(): Identity {
        val pub = ByteArray(32)
        val secret = ByteArray(64)
        check(sodium.cryptoSignKeypair(pub, secret))
        return Identity(pub, secret)
    }

    /**
     * [proofVersion] 2 signs the v2 transcript (what 1.0 apps send); 0 signs the 0.x transcript and leaves
     * the field unset like a 0.x app; [labelVersion] overrides the advertised version without re-signing.
     */
    private fun listenerHello(
        id: Identity,
        ts: Long = System.currentTimeMillis(),
        proofVersion: Int = RelayProof.PROOF_VERSION_V2,
        labelVersion: Int = proofVersion,
    ): ByteArray {
        val transcript = if (proofVersion == RelayProof.PROOF_VERSION_V2) {
            RelayProof.buildListenerProofTranscript(id.hash, id.pub, ts)
        } else {
            RelayProof.buildLegacyListenerProofTranscript(id.hash, ts)
        }
        val proof = ByteArray(64)
        check(sodium.cryptoSignDetached(proof, transcript, transcript.size.toLong(), id.secret))
        return RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_LISTENER)
            .setListenerId(ByteString.copyFrom(id.hash))
            .setIdentityPub(ByteString.copyFrom(id.pub))
            .setProof(ByteString.copyFrom(proof))
            .setTs(ts)
            .setProofVersion(labelVersion)
            .build()
            .toByteArray()
    }

    private fun dialerHello(targetId: ByteArray, circuitId: String = ""): ByteArray =
        RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_DIALER)
            .setTargetId(ByteString.copyFrom(targetId))
            .setCircuitId(circuitId)
            .build()
            .toByteArray()

    private fun acceptHello(circuitId: String): ByteArray =
        RelayHello.newBuilder()
            .setRole(RelayRole.RELAY_ROLE_ACCEPT)
            .setCircuitId(circuitId)
            .build()
            .toByteArray()

    private fun newState(config: NodeConfig = NodeConfig()): RelayNodeState =
        RelayNodeState(config, ByteArray(32) { 0x11 })

    private fun relayTest(
        state: RelayNodeState,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application { relayNodeModule(state) }
        val client = createClient { install(ClientWebSockets) }
        block(client)
    }

    private suspend fun DefaultClientWebSocketSession.sendBytes(bytes: ByteArray) =
        send(Frame.Binary(true, bytes))

    private suspend fun DefaultClientWebSocketSession.readBinary(): ByteArray = withTimeout(READ_TIMEOUT_MS) {
        (incoming.receive() as Frame.Binary).readBytes()
    }

    private suspend fun DefaultClientWebSocketSession.readEvent(): RelayEvent = RelayEvent.parseFrom(readBinary())

    private suspend fun DefaultClientWebSocketSession.expectError(): String {
        val event = readEvent()
        assertEquals(RelayEventType.RELAY_EVENT_TYPE_ERROR, event.type)
        return event.message
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(READ_TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    @Test
    fun `valid listener registers`() {
        val state = newState()
        val id = identity()
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(listenerHello(id))
                awaitUntil { state.listeners.size == 1 }
                assertEquals(1, state.stats.listeners.get())
                assertNull(withTimeoutOrNull(QUIET_MS) { incoming.receive() })
            }
            awaitUntil { state.listeners.isEmpty() }
            assertEquals(0, state.stats.listeners.get())
        }
    }

    @Test
    fun `legacy v1 listener proof still registers during the transition`() {
        val state = newState()
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(listenerHello(identity(), proofVersion = 0))
                awaitUntil { state.listeners.size == 1 }
            }
            client.webSocket("/relay") {
                sendBytes(listenerHello(identity(), proofVersion = 0, labelVersion = 1))
                awaitUntil { state.listeners.size == 1 }
            }
            awaitUntil { state.listeners.isEmpty() }
            assertEquals(0, state.stats.rejectedInvalidHello.get())
        }
    }

    @Test
    fun `proof signed over the wrong transcript version is rejected`() {
        val state = newState()
        relayTest(state) { client ->
            // v1-signed bytes labelled as v2, and v2-signed bytes labelled as legacy.
            client.webSocket("/relay") {
                sendBytes(listenerHello(identity(), proofVersion = 0, labelVersion = 2))
                assertEquals("Invalid listener proof", expectError())
            }
            client.webSocket("/relay") {
                sendBytes(listenerHello(identity(), proofVersion = 2, labelVersion = 0))
                assertEquals("Invalid listener proof", expectError())
            }
            client.webSocket("/relay") {
                sendBytes(listenerHello(identity(), proofVersion = 2, labelVersion = 3))
                assertEquals("Invalid listener proof", expectError())
            }
            assertEquals(3, state.stats.rejectedInvalidHello.get())
            assertTrue(state.listeners.isEmpty())
        }
    }

    @Test
    fun `v2 proof from another identity key is rejected`() {
        val state = newState()
        val id = identity()
        val impostor = identity()
        relayTest(state) { client ->
            client.webSocket("/relay") {
                // Correct listener_id/identity_pub pair, but the proof was made with someone else's key.
                val hello = RelayHello.parseFrom(listenerHello(impostor)).toBuilder()
                    .setListenerId(ByteString.copyFrom(id.hash))
                    .setIdentityPub(ByteString.copyFrom(id.pub))
                    .build()
                sendBytes(hello.toByteArray())
                assertEquals("Invalid listener proof", expectError())
            }
            assertTrue(state.listeners.isEmpty())
        }
    }

    @Test
    fun `stale listener proof is rejected`() {
        val state = newState()
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(listenerHello(identity(), ts = System.currentTimeMillis() - TEN_MINUTES_MS))
                assertEquals("Stale listener proof", expectError())
            }
            assertEquals(1, state.stats.rejectedStaleProof.get())
            assertTrue(state.listeners.isEmpty())
        }
    }

    @Test
    fun `replayed listener hello is rejected`() {
        val state = newState()
        val hello = listenerHello(identity())
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(hello)
                awaitUntil { state.listeners.size == 1 }
                client.webSocket("/relay") {
                    sendBytes(hello)
                    assertEquals("Replayed listener proof", expectError())
                }
            }
            assertEquals(1, state.stats.rejectedReplayedProof.get())
        }
    }

    @Test
    fun `dial to unknown target reports peer not listening`() {
        relayTest(newState()) { client ->
            client.webSocket("/relay") {
                sendBytes(dialerHello(ByteArray(32) { 0x7f }))
                assertEquals("Peer not listening on relay", expectError())
            }
        }
    }

    @Test
    fun `dial accept and bridge both ways`() {
        val state = newState()
        val id = identity()
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(listenerHello(id))
                awaitUntil { state.listeners.size == 1 }
                val dialer = launch {
                    client.webSocket("/relay") {
                        sendBytes(dialerHello(id.hash))
                        assertEquals(RelayEventType.RELAY_EVENT_TYPE_READY, readEvent().type)
                        sendBytes("ping".toByteArray())
                        assertEquals("pong", String(readBinary()))
                    }
                }
                val incomingEvent = readEvent()
                assertEquals(RelayEventType.RELAY_EVENT_TYPE_INCOMING, incomingEvent.type)
                assertTrue(incomingEvent.circuitId.isNotBlank())
                client.webSocket("/relay") {
                    sendBytes(acceptHello(incomingEvent.circuitId))
                    assertEquals(RelayEventType.RELAY_EVENT_TYPE_READY, readEvent().type)
                    assertEquals(1, state.stats.activeCircuits.get())
                    assertEquals("ping", String(readBinary()))
                    sendBytes("pong".toByteArray())
                    dialer.join()
                }
            }
            awaitUntil { state.stats.activeCircuits.get() == 0 && state.pendingDialers.isEmpty() }
            assertEquals(0, state.stats.pendingDialers.get())
        }
    }

    /**
     * What a call's audio relies on (the app's CallMediaService): a dialer-chosen circuit name reaches
     * the listener verbatim, since the listener routes a call's circuits by it; small frames cross in
     * order and unchanged in both directions; and one end hanging up closes the other at once, rather
     * than leaving it to idle out while a call waits on it.
     */
    @Test
    fun `a named circuit carries a call's frames unchanged, and one end's close closes the other`() {
        val state = newState()
        val id = identity()
        val name = "vmcall-" + "ab".repeat(16) + "-3"
        val frames = List(CALL_FRAMES) { index -> ByteArray(4 + index) { (index * 7 + it).toByte() } }
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(listenerHello(id))
                awaitUntil { state.listeners.size == 1 }
                val dialer = launch {
                    client.webSocket("/relay") {
                        sendBytes(dialerHello(id.hash, circuitId = name))
                        assertEquals(RelayEventType.RELAY_EVENT_TYPE_READY, readEvent().type)
                        frames.forEach { sendBytes(it) }
                        frames.forEach { assertArrayEquals(it, readBinary()) }
                    }
                }
                val incomingEvent = readEvent()
                assertEquals(RelayEventType.RELAY_EVENT_TYPE_INCOMING, incomingEvent.type)
                assertEquals(name, incomingEvent.circuitId)
                client.webSocket("/relay") {
                    sendBytes(acceptHello(name))
                    assertEquals(RelayEventType.RELAY_EVENT_TYPE_READY, readEvent().type)
                    frames.forEach { assertArrayEquals(it, readBinary()) }
                    frames.forEach { sendBytes(it) }
                    dialer.join()
                    val reason = withTimeout(READ_TIMEOUT_MS) { closeReason.await() }
                    assertEquals("peer closed", reason?.message)
                }
            }
            awaitUntil { state.stats.activeCircuits.get() == 0 && state.pendingDialers.isEmpty() }
        }
    }

    @Test
    fun `dial without accept times out`() {
        val state = newState(NodeConfig(pendingDialerTtlMs = 200))
        val id = identity()
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(listenerHello(id))
                awaitUntil { state.listeners.size == 1 }
                val dialer = launch {
                    client.webSocket("/relay") {
                        sendBytes(dialerHello(id.hash))
                        assertEquals("Relay accept timed out", expectError())
                    }
                }
                assertEquals(RelayEventType.RELAY_EVENT_TYPE_INCOMING, readEvent().type)
                dialer.join()
                awaitUntil { state.pendingDialers.isEmpty() }
            }
        }
    }

    @Test
    fun `duplicate circuit_id is rejected without leaking the first dialer`() {
        val state = newState()
        val id = identity()
        relayTest(state) { client ->
            client.webSocket("/relay") {
                sendBytes(listenerHello(id))
                awaitUntil { state.listeners.size == 1 }
                val listener = state.listeners.values.single()
                val release = CompletableDeferred<Unit>()
                val first = launch {
                    client.webSocket("/relay") {
                        sendBytes(dialerHello(id.hash, circuitId = "dup"))
                        release.await()
                    }
                }
                val incomingEvent = readEvent()
                assertEquals(RelayEventType.RELAY_EVENT_TYPE_INCOMING, incomingEvent.type)
                assertEquals("dup", incomingEvent.circuitId)
                assertEquals(1, listener.pending.get())
                client.webSocket("/relay") {
                    sendBytes(dialerHello(id.hash, circuitId = "dup"))
                    assertEquals("Duplicate circuit_id", expectError())
                }
                assertEquals(1, state.stats.rejectedInvalidHello.get())
                // The original dialer is still the one registered for "dup"...
                assertEquals(1, state.pendingDialers.size)
                // ...and the rejected dialer's slot is released once its handler
                // unwinds — that happens after the error frame reaches the client,
                // so poll instead of asserting the counter immediately.
                awaitUntil { listener.pending.get() == 1 }
                // Hanging up the original dialer releases everything it held.
                release.complete(Unit)
                first.join()
                awaitUntil { state.pendingDialers.isEmpty() && listener.pending.get() == 0 }
                assertEquals(0, state.stats.pendingDialers.get())
            }
        }
    }

    @Test
    fun `dials beyond the burst are rate limited`() {
        val state = newState(NodeConfig(dialRatePerMin = 1, dialBurst = 2))
        relayTest(state) { client ->
            repeat(2) {
                client.webSocket("/relay") {
                    sendBytes(dialerHello(ByteArray(32) { 0x7f }))
                    assertEquals("Peer not listening on relay", expectError())
                }
            }
            client.webSocket("/relay") {
                sendBytes(dialerHello(ByteArray(32) { 0x7f }))
                assertEquals("Rate limited", expectError())
            }
            assertEquals(1, state.stats.rejectedRateLimited.get())
        }
    }

    @Test
    fun `healthz answers ok`() {
        relayTest(newState()) { client ->
            val response = client.get("/healthz")
            assertEquals("ok", response.bodyAsText())
            assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
        }
    }

    @Test
    fun `verbose healthz is json with counters`() {
        relayTest(newState()) { client ->
            val response = client.get("/healthz?verbose=1")
            val body = response.bodyAsText()
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertTrue(body, body.startsWith("{") && body.endsWith("}"))
            assertTrue(body, body.contains("\"listeners\":0"))
            assertTrue(body, body.contains("\"uptimeSec\":"))
            assertTrue(body, body.contains("\"config\":\""))
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 5_000L

        /** A second of audio at fifty frames a second. */
        const val CALL_FRAMES = 50
        const val QUIET_MS = 300L
        const val POLL_MS = 10L
        const val TEN_MINUTES_MS = 10L * 60 * 1000
    }
}
