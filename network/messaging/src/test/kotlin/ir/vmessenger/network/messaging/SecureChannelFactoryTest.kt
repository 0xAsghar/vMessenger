package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.ProtocolVersion
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.wire.v1.Capabilities
import ir.vmessenger.core.proto.wire.v1.Close
import ir.vmessenger.core.proto.wire.v1.CloseCode
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.core.proto.wire.v1.HandshakeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecureChannelFactoryTest {
    private lateinit var crypto: CryptoEngine
    private lateinit var factory: SecureChannelFactory
    private lateinit var alice: PeerIdentity
    private lateinit var bob: PeerIdentity
    private lateinit var aliceEd: KeyPair
    private lateinit var bobEd: KeyPair
    private lateinit var bobX: KeyPair

    @Before
    fun setUp() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        factory = SecureChannelFactory(crypto, SymmetricRatchet(crypto))
        aliceEd = crypto.generateEd25519KeyPair()
        bobEd = crypto.generateEd25519KeyPair()
        bobX = crypto.generateX25519KeyPair()
        alice = peer(crypto.sha256(aliceEd.publicKey), aliceEd, crypto.generateX25519KeyPair())
        bob = peer(crypto.sha256(bobEd.publicKey), bobEd, bobX)
    }

    @Test
    fun handshakeRoundTrip() = runBlocking {
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice).getOrThrow() }
        val clientSession = factory.initiate(client, alice, bob).getOrThrow()
        val serverSession = serverDeferred.await()
        clientSession.close()
        serverSession.close()
    }

    @Test
    fun x25519SharedSecretIsSymmetric() {
        val eph = crypto.generateX25519KeyPair()
        val staticKey = crypto.generateX25519KeyPair()
        val ab = crypto.x25519SharedSecret(eph.privateKey, staticKey.publicKey)
        val ba = crypto.x25519SharedSecret(staticKey.privateKey, eph.publicKey)
        assertTrue(ab.contentEquals(ba))
    }

    @Test
    fun handshakeRootKeysMatch() = runBlocking {
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice).getOrThrow() }
        val clientSession = factory.initiate(client, alice, bob).getOrThrow()
        val serverSession = serverDeferred.await()

        assertTrue(clientSession.ratchetState.sendChainKey.contentEquals(serverSession.ratchetState.recvChainKey))
        assertTrue(clientSession.ratchetState.recvChainKey.contentEquals(serverSession.ratchetState.sendChainKey))

        clientSession.close()
        serverSession.close()
    }

    @Test
    fun bobInitiatesAliceAcceptsEncryptsAndDecrypts() = runBlocking {
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, alice, bob).getOrThrow() }
        val clientSession = factory.initiate(client, bob, alice).getOrThrow()
        val serverSession = serverDeferred.await()

        val payload = "reply".toByteArray()
        val sealed = clientSession.seal(payload)
        val counter = serverSession.ratchetState.recvCounter + 1
        val opened = serverSession.open(sealed, counter)
        assertTrue(opened?.contentEquals(payload) == true)

        clientSession.close()
        serverSession.close()
    }

    @Test
    fun handshakeEncryptsAndDecrypts() = runBlocking {
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice).getOrThrow() }
        val clientSession = factory.initiate(client, alice, bob).getOrThrow()
        val serverSession = serverDeferred.await()

        val payload = "سلام".toByteArray(Charsets.UTF_8)
        val sealed = clientSession.seal(payload)
        val counter = serverSession.ratchetState.recvCounter + 1
        val opened = serverSession.open(sealed, counter)
        assertTrue(opened?.contentEquals(payload) == true)

        clientSession.close()
        serverSession.close()
    }

    @Test
    fun acceptResolvingEncryptsAndDecrypts() = runBlocking {
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) {
            factory.acceptResolving(server, bob) { identityPub, staticPub ->
                require(identityPub.contentEquals(aliceEd.publicKey))
                alice.copy(x25519StaticPublicKey = staticPub)
            }.getOrThrow()
        }
        val clientSession = factory.initiate(client, alice, placeholderBob()).getOrThrow()
        val serverSession = serverDeferred.await()

        assertTrue(clientSession.peer.ed25519PublicKey.contentEquals(bobEd.publicKey))
        assertTrue(clientSession.peer.x25519StaticPublicKey.contentEquals(bobX.publicKey))

        val payload = "test message".toByteArray()
        val sealed = clientSession.seal(payload)
        val counter = serverSession.ratchetState.recvCounter + 1
        val opened = serverSession.open(sealed, counter)
        assertTrue(opened?.contentEquals(payload) == true)

        clientSession.close()
        serverSession.close()
    }

    @Test
    fun initiateLearnsPeerKeysFromHandshake() = runBlocking {
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) {
            factory.acceptResolving(server, bob) { identityPub, staticPub ->
                require(identityPub.contentEquals(aliceEd.publicKey))
                alice.copy(x25519StaticPublicKey = staticPub)
            }.getOrThrow()
        }
        val clientSession = factory.initiate(client, alice, placeholderBob()).getOrThrow()
        val serverSession = serverDeferred.await()

        assertTrue(clientSession.peer.ed25519PublicKey.contentEquals(bobEd.publicKey))
        assertTrue(clientSession.peer.x25519StaticPublicKey.contentEquals(bobX.publicKey))
        assertTrue(clientSession.peer.identityHash.contentEquals(crypto.sha256(bobEd.publicKey)))
        clientSession.close()
        serverSession.close()
    }

    @Test
    fun mitmSubstitutedResponderKeysRejected() = runBlocking {
        val attackerEph = crypto.generateX25519KeyPair()
        val attackerStatic = crypto.generateX25519KeyPair()
        // Attacker sits between the pipes and rewrites Bob's step 2 keys while
        // keeping Bob's genuine signature.
        val (client, server) = pairedConnections(serverToClient = { bytes ->
            rewriteStep(bytes, step = 2) { step ->
                step.setEphemeralPub(ByteString.copyFrom(attackerEph.publicKey))
                    .setStaticPub(ByteString.copyFrom(attackerStatic.publicKey))
            }
        })

        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice) }
        val result = factory.initiate(client, alice, bob)
        assertTrue("initiator must reject substituted responder keys", result.isFailure)
        assertTrue(
            "expected signature failure, got ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("signature") == true,
        )
        client.close()
        server.close()
        serverDeferred.await()
        Unit
    }

    @Test
    fun mitmSubstitutedInitiatorStaticRejected() = runBlocking {
        val attackerStatic = crypto.generateX25519KeyPair()
        val (client, server) = pairedConnections(clientToServer = { bytes ->
            rewriteStep(bytes, step = 3) { step ->
                step.setStaticPub(ByteString.copyFrom(attackerStatic.publicKey))
            }
        })

        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice) }
        factory.initiate(client, alice, bob)
        val result = serverDeferred.await()
        assertTrue("responder must reject substituted initiator static key", result.isFailure)
        assertTrue(
            "expected signature failure, got ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("signature") == true,
        )
        client.close()
        server.close()
    }

    @Test
    fun oldMajorInitiatorRejectedWithCloseFrame() = runBlocking {
        val (client, server) = pairedConnections()
        val v1Step1 = HandshakeMessage.newBuilder()
            .setStep(1)
            .setEphemeralPub(ByteString.copyFrom(crypto.generateX25519KeyPair().publicKey))
            .setCapabilities(Capabilities.newBuilder().setProtocolMajor(1).setProtocolMinor(0))
            .build()
        val v1Frame = Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_HANDSHAKE)
            .setBody(v1Step1.toByteString())
            .build()
        client.write(v1Frame.toByteArray()).getOrThrow()

        val result = factory.accept(server, bob, alice)
        val error = result.exceptionOrNull()
        assertTrue("expected ProtocolVersionException, got $error", error is ProtocolVersionException)
        assertEquals(1, (error as ProtocolVersionException).peerMajor)

        val reply = Frame.parseFrom(withTimeout(2_000) { client.read().first() })
        assertEquals(ProtocolVersion.MAJOR, reply.version)
        assertEquals(FrameType.FRAME_TYPE_CLOSE, reply.type)
        val close = Close.parseFrom(reply.body)
        assertEquals(CloseCode.CLOSE_CODE_VERSION_MISMATCH.number, close.code)
        assertEquals(ProtocolVersion.MAJOR, close.supportedMajor)
        client.close()
        server.close()
    }

    @Test
    fun oldMajorResponderRejected() = runBlocking {
        val (client, server) = pairedConnections()
        val oldResponder = async(Dispatchers.Default) {
            val step1 = Frame.parseFrom(server.read().first())
            assertEquals(ProtocolVersion.MAJOR, step1.version)
            val v1Step2 = HandshakeMessage.newBuilder()
                .setStep(2)
                .setEphemeralPub(ByteString.copyFrom(bobX.publicKey))
                .setStaticPub(ByteString.copyFrom(bobX.publicKey))
                .setIdentityPub(ByteString.copyFrom(bobEd.publicKey))
                .setCapabilities(Capabilities.newBuilder().setProtocolMajor(1))
                .build()
            server.write(
                Frame.newBuilder()
                    .setVersion(1)
                    .setType(FrameType.FRAME_TYPE_HANDSHAKE)
                    .setBody(v1Step2.toByteString())
                    .build()
                    .toByteArray(),
            ).getOrThrow()
            Frame.parseFrom(withTimeout(2_000) { server.read().first() })
        }
        val result = factory.initiate(client, alice, bob)
        val error = result.exceptionOrNull()
        assertTrue("expected ProtocolVersionException, got $error", error is ProtocolVersionException)
        assertEquals(1, (error as ProtocolVersionException).peerMajor)
        val closeFrame = oldResponder.await()
        assertEquals(FrameType.FRAME_TYPE_CLOSE, closeFrame.type)
        assertEquals(ProtocolVersion.MAJOR, Close.parseFrom(closeFrame.body).supportedMajor)
        client.close()
        server.close()
    }

    @Test
    fun bothSidesDeriveEqualChainsWithDh3() = runBlocking {
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice).getOrThrow() }
        val clientSession = factory.initiate(client, alice, bob).getOrThrow()
        val serverSession = serverDeferred.await()
        assertTrue(clientSession.ratchetState.sendChainKey.contentEquals(serverSession.ratchetState.recvChainKey))
        assertTrue(clientSession.ratchetState.recvChainKey.contentEquals(serverSession.ratchetState.sendChainKey))
        clientSession.close()
        serverSession.close()

        // dh3 = X25519(s_I, e_R): an initiator whose static private key does not
        // match its advertised static public key still passes both signatures
        // but must end up with different chains — proving s_I feeds the root.
        val mismatchedAlice = alice.copy(x25519StaticPrivateKey = crypto.generateX25519KeyPair().privateKey)
        val (client2, server2) = pairedConnections()
        val server2Deferred = async(Dispatchers.Default) { factory.accept(server2, bob, alice).getOrThrow() }
        val client2Session = factory.initiate(client2, mismatchedAlice, bob).getOrThrow()
        val server2Session = server2Deferred.await()
        assertFalse(client2Session.ratchetState.sendChainKey.contentEquals(server2Session.ratchetState.recvChainKey))
        client2Session.close()
        server2Session.close()
    }

    @Test
    fun handshakeFrameOver4KiBRejected() = runBlocking {
        val (client, server) = pairedConnections()
        client.write(crypto.randomBytes(SecureChannelFactory.MAX_HANDSHAKE_FRAME_BYTES + 1)).getOrThrow()
        val result = factory.accept(server, bob, alice)
        assertTrue(result.isFailure)
        assertTrue(
            "expected size rejection, got ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("too large") == true,
        )
        client.close()
        server.close()
    }

    @Test
    fun pinnedStaticKeyMismatchAborts() = runBlocking {
        val pinnedBob = bob.copy(x25519StaticPublicKey = crypto.generateX25519KeyPair().publicKey)
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, alice) }
        val result = factory.initiate(client, alice, pinnedBob)
        val error = result.exceptionOrNull()
        assertTrue("expected PeerKeyChangedException, got $error", error is PeerKeyChangedException)
        assertTrue((error as PeerKeyChangedException).newStaticKey.contentEquals(bobX.publicKey))
        client.close()
        server.close()
        assertTrue(serverDeferred.await().isFailure)
    }

    @Test
    fun pinnedStaticKeyMismatchAbortsOnResponder() = runBlocking {
        // Bob pinned a different static key for Alice; her genuine step 3 must be refused.
        val pinnedAlice = alice.copy(x25519StaticPublicKey = crypto.generateX25519KeyPair().publicKey)
        val (client, server) = pairedConnections()
        val serverDeferred = async(Dispatchers.Default) { factory.accept(server, bob, pinnedAlice) }
        val clientResult = factory.initiate(client, alice, bob)
        val error = serverDeferred.await().exceptionOrNull()
        assertTrue("expected PeerKeyChangedException, got $error", error is PeerKeyChangedException)
        assertTrue((error as PeerKeyChangedException).newStaticKey.contentEquals(alice.x25519StaticPublicKey))
        assertTrue(error.identityHash.contentEquals(crypto.sha256(aliceEd.publicKey)))
        clientResult.getOrNull()?.close()
        client.close()
        server.close()
    }

    private fun placeholderBob(): PeerIdentity {
        val bobHash = crypto.sha256(bobEd.publicKey)
        return PeerIdentity(
            identityHash = ByteArray(32).also { bobHash.copyInto(it, 0, 0, 16) },
            ed25519PublicKey = ByteArray(32),
            x25519StaticPublicKey = ByteArray(32),
        )
    }

    private fun rewriteStep(
        bytes: ByteArray,
        step: Int,
        edit: (HandshakeMessage.Builder) -> HandshakeMessage.Builder,
    ): ByteArray {
        val frame = Frame.parseFrom(bytes)
        val message = HandshakeMessage.parseFrom(frame.body)
        return if (frame.type != FrameType.FRAME_TYPE_HANDSHAKE || message.step != step) {
            bytes
        } else {
            val forged = edit(message.toBuilder()).build()
            frame.toBuilder().setBody(forged.toByteString()).build().toByteArray()
        }
    }

    private fun peer(hash: ByteArray, ed: KeyPair, x: KeyPair) = PeerIdentity(
        identityHash = hash,
        ed25519PublicKey = ed.publicKey,
        x25519StaticPublicKey = x.publicKey,
        ed25519PrivateKey = ed.privateKey,
        x25519StaticPrivateKey = x.privateKey,
    )
}
