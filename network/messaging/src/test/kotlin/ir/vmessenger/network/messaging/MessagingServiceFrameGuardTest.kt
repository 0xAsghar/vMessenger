package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.Canonical
import ir.vmessenger.core.common.network.ProtocolVersion
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.CloseCode
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.network.transport.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Frame-level guards applied by [MessagingService] through [SecureFrameGuard]:
 * version on every frame, CLOSE handling, counter >= 1, and the session cap.
 */
class MessagingServiceFrameGuardTest {
    private lateinit var crypto: CryptoEngine
    private lateinit var factory: SecureChannelFactory
    private lateinit var aliceSession: ActiveSecureSession
    private lateinit var bobSession: ActiveSecureSession
    private val guard = SecureFrameGuard()

    @Before
    fun setUp() = runBlocking {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        factory = SecureChannelFactory(crypto, SymmetricRatchet(crypto))
        val alice = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        val bob = identity(crypto.generateEd25519KeyPair(), crypto.generateX25519KeyPair())
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice).getOrThrow() }
        aliceSession = factory.initiate(client, alice, bob).getOrThrow() as ActiveSecureSession
        bobSession = serverDeferred.await() as ActiveSecureSession
    }

    @Test
    fun validFrameIsDelivered() = runBlocking {
        aliceSession.writeSealed(envelope("hi"))
        val outcome = guard.process(bobSession, CONTACT, nextFrameForBob())
        assertTrue(outcome is SecureFrameOutcome.Envelope)
        assertEquals("hi", (outcome as SecureFrameOutcome.Envelope).envelope.chat.text)
        assertFalse(outcome.sessionExpired)
        assertEquals(ConnectionState.OPEN, bobSession.connection.state.value)
    }

    @Test
    fun secureFrameWithWrongVersionClosesSession() = runBlocking {
        val sealed = aliceSession.seal(envelope("v1").toByteArray())
        val frame = Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(ByteString.copyFrom(sealed))
            .setCounter(aliceSession.ratchetState.sendCounter)
            .build()
        val outcome = guard.process(bobSession, CONTACT, frame.toByteArray())
        assertTrue("expected Closed, got $outcome", outcome is SecureFrameOutcome.Closed)
        assertEquals(ConnectionState.CLOSED, bobSession.connection.state.value)
    }

    @Test
    fun closeFrameClosesSession() = runBlocking {
        val bytes = CloseFrames.encode(CloseCode.CLOSE_CODE_REJECTED, "bye")
        val outcome = guard.process(bobSession, CONTACT, bytes)
        assertTrue(outcome is SecureFrameOutcome.Closed)
        assertEquals(ConnectionState.CLOSED, bobSession.connection.state.value)
    }

    @Test
    fun counterZeroRejected() = runBlocking {
        val sealed = aliceSession.seal(envelope("zero").toByteArray())
        val frame = Frame.newBuilder()
            .setVersion(ProtocolVersion.MAJOR)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(ByteString.copyFrom(sealed))
            .setCounter(0)
            .build()
        assertEquals(SecureFrameOutcome.Ignored, guard.process(bobSession, CONTACT, frame.toByteArray()))
        assertEquals(0L, bobSession.ratchetState.recvCounter)
        assertEquals(ConnectionState.OPEN, bobSession.connection.state.value)
        // A tampered counter fails authentication (counter is bound into the AD).
        val forged = frame.toBuilder().setCounter(2).build()
        assertEquals(SecureFrameOutcome.Ignored, guard.process(bobSession, CONTACT, forged.toByteArray()))
        assertEquals(0L, bobSession.ratchetState.recvCounter)
        // The genuine frame (counter 1) still opens afterwards.
        val genuine = frame.toBuilder().setCounter(1).build()
        assertTrue(guard.process(bobSession, CONTACT, genuine.toByteArray()) is SecureFrameOutcome.Envelope)
    }

    @Test
    fun sessionExpiresAfterMaxFrames() = runBlocking {
        aliceSession.ratchetState.sendCounter = ActiveSecureSession.MAX_SESSION_FRAMES - 1
        bobSession.ratchetState.recvCounter = ActiveSecureSession.MAX_SESSION_FRAMES - 1
        assertFalse(bobSession.isExpired())
        aliceSession.writeSealed(envelope("last"))
        val outcome = guard.process(bobSession, CONTACT, nextFrameForBob())
        assertTrue(outcome is SecureFrameOutcome.Envelope)
        assertTrue(
            "session must be flagged expired at the cap",
            (outcome as SecureFrameOutcome.Envelope).sessionExpired,
        )
        assertTrue(bobSession.isExpired())
    }

    @Test
    fun sessionExpiresAfterMaxAge() {
        val aged = ActiveSecureSession(
            peer = bobSession.peer,
            selfPublicKeyHash = ByteArray(32),
            peerPublicKeyHash = ByteArray(32),
            ratchetState = bobSession.ratchetState,
            ratchet = SymmetricRatchet(crypto),
            connection = bobSession.connection,
            openedAtUnixMs = 1_000L,
        )
        assertFalse(aged.isExpired(nowUnixMs = 1_000L + ActiveSecureSession.MAX_SESSION_AGE_MS - 1))
        assertTrue(aged.isExpired(nowUnixMs = 1_000L + ActiveSecureSession.MAX_SESSION_AGE_MS))
    }

    @Test
    fun framesAfterCloseAreDropped() = runBlocking {
        // A peer CLOSE (or version mismatch / frame cap) closes Bob's session and
        // wipes his ratchet to all-zero chains ...
        val closing = guard.process(bobSession, CONTACT, CloseFrames.encode(CloseCode.CLOSE_CODE_REJECTED, "bye"))
        assertTrue(closing is SecureFrameOutcome.Closed)
        assertTrue(bobSession.isClosed)
        assertTrue(bobSession.ratchetState.wiped)
        // ... after which mk = HKDF(0^32, "vmsg-v2-mk" || u64be(recv+1)) and the AD are
        // public, so an on-path party can seal a frame Bob's transport still delivers.
        val counter = bobSession.ratchetState.recvCounter + 1
        val senderKeyHash = crypto.sha256(bobSession.peer.ed25519PublicKey)
        val ad = FrameAssociatedData.prefix(FrameType.FRAME_TYPE_SECURE, senderKeyHash)
        val zeroChainMessageKey = crypto.hkdfSha256(
            ByteArray(32),
            ByteArray(0),
            "vmsg-v2-mk".toByteArray() + Canonical.u64be(counter),
            32,
        )
        val fullAd = ad + Canonical.u64be(counter)
        val forgedBody = crypto.seal(envelope("forged").toByteArray(), zeroChainMessageKey, fullAd)
        val forged = Frame.newBuilder()
            .setVersion(ProtocolVersion.MAJOR)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(ByteString.copyFrom(forgedBody))
            .setCounter(counter)
            .build()
        val outcome = guard.process(bobSession, CONTACT, forged.toByteArray())
        assertFalse("forged frame must not be delivered after close: $outcome", outcome is SecureFrameOutcome.Envelope)
        assertNull(bobSession.open(forgedBody, counter, FrameType.FRAME_TYPE_SECURE))
        assertEquals(counter - 1, bobSession.ratchetState.recvCounter)
        // Sanity: the forgery is exactly what a zeroed-but-unguarded chain would accept.
        val unguarded = RatchetState(
            sendChainKey = ByteArray(32),
            recvChainKey = ByteArray(32),
            recvCounter = counter - 1,
        )
        assertNotNull(SymmetricRatchet(crypto).open(unguarded, forgedBody, counter, ad))
    }

    @Test
    fun writeSealedRefusedAfterClose() = runBlocking {
        aliceSession.close()
        val result = runCatching { aliceSession.writeSealed(envelope("late")) }
        assertTrue(result.isFailure)
    }

    private suspend fun nextFrameForBob(): ByteArray = withTimeout(2_000) { bobSession.connection.read().first() }

    private fun envelope(text: String): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("id-$text"))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setCounter(1)
        .setChat(ir.vmessenger.core.proto.app.v1.ChatMessage.newBuilder().setText(text))
        .build()

    private fun identity(ed: KeyPair, x: KeyPair) = PeerIdentity(
        identityHash = crypto.sha256(ed.publicKey),
        ed25519PublicKey = ed.publicKey,
        x25519StaticPublicKey = x.publicKey,
        ed25519PrivateKey = ed.privateKey,
        x25519StaticPrivateKey = x.privateKey,
    )

    private companion object {
        const val CONTACT = "contact-1"
    }
}
