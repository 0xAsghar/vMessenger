package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.wire.v1.Capabilities
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.core.proto.wire.v1.HandshakeMessage
import ir.vmessenger.network.transport.Connection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

data class PeerIdentity(
    val identityHash: ByteArray,
    val ed25519PublicKey: ByteArray,
    val x25519StaticPublicKey: ByteArray,
    val ed25519PrivateKey: ByteArray? = null,
    val x25519StaticPrivateKey: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerIdentity
        return identityHash.contentEquals(other.identityHash) &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            x25519StaticPublicKey.contentEquals(other.x25519StaticPublicKey)
    }

    override fun hashCode(): Int {
        var result = identityHash.contentHashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + x25519StaticPublicKey.contentHashCode()
        return result
    }
}

interface SecureSession {
    val peer: PeerIdentity
    val ratchetState: RatchetState
    suspend fun seal(plaintext: ByteArray): ByteArray
    suspend fun open(frame: ByteArray, counter: Long): ByteArray?
    suspend fun close()
}

class ActiveSecureSession(
    override val peer: PeerIdentity,
    override val ratchetState: RatchetState,
    private val ratchet: SymmetricRatchet,
    private val connection: Connection,
) : SecureSession {
    override suspend fun seal(plaintext: ByteArray): ByteArray =
        ratchet.seal(ratchetState, plaintext, peer.identityHash)

    override suspend fun open(frame: ByteArray, counter: Long): ByteArray? =
        ratchet.open(ratchetState, frame, counter, peer.identityHash)

    override suspend fun close() {
        connection.close()
    }

    suspend fun writeFrame(frame: ByteArray) {
        connection.write(frame).getOrThrow()
    }
}

@Singleton
class SecureChannelFactory @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val ratchet: SymmetricRatchet,
) {
    suspend fun initiate(connection: Connection, self: PeerIdentity, peer: PeerIdentity): Result<SecureSession> =
        runCatching {
            val eph = cryptoEngine.generateX25519KeyPair()
            val step1 = HandshakeMessage.newBuilder()
                .setStep(1)
                .setEphemeralPub(ByteString.copyFrom(eph.publicKey))
                .setCapabilities(defaultCapabilities())
                .build()
            sendHandshake(connection, step1)
            val step2 = readHandshake(connection)
            require(step2.step == 2) { "Expected handshake step 2" }
            verifyPeer(step2, peer)
            val dh1 = cryptoEngine.x25519SharedSecret(eph.privateKey, step2.ephemeralPub.toByteArray())
            val dh2 = cryptoEngine.x25519SharedSecret(
                self.x25519StaticPrivateKey!!,
                step2.ephemeralPub.toByteArray(),
            )
            val root = cryptoEngine.hkdfSha256(
                dh1 + dh2,
                ByteArray(0),
                "vmessenger-handshake".toByteArray(),
                32,
            )
            val transcript = buildTranscript(step1, step2)
            val step3 = HandshakeMessage.newBuilder()
                .setStep(3)
                .setStaticPub(ByteString.copyFrom(self.x25519StaticPublicKey))
                .setIdentityPub(ByteString.copyFrom(self.ed25519PublicKey))
                .setSignature(
                    ByteString.copyFrom(
                        cryptoEngine.signEd25519(transcript, self.ed25519PrivateKey!!),
                    ),
                )
                .setCapabilities(defaultCapabilities())
                .build()
            sendHandshake(connection, step3)
            val state = ratchet.initFromRoot(root, isInitiator = true)
            ActiveSecureSession(peer, state, ratchet, connection)
        }

    suspend fun accept(connection: Connection, self: PeerIdentity, expectedPeer: PeerIdentity): Result<SecureSession> =
        runCatching {
            val step1 = readHandshake(connection)
            require(step1.step == 1) { "Expected handshake step 1" }
            val eph = cryptoEngine.generateX25519KeyPair()
            val dh1 = cryptoEngine.x25519SharedSecret(eph.privateKey, step1.ephemeralPub.toByteArray())
            val step2 = HandshakeMessage.newBuilder()
                .setStep(2)
                .setEphemeralPub(ByteString.copyFrom(eph.publicKey))
                .setStaticPub(ByteString.copyFrom(self.x25519StaticPublicKey))
                .setIdentityPub(ByteString.copyFrom(self.ed25519PublicKey))
                .setSignature(
                    ByteString.copyFrom(
                        cryptoEngine.signEd25519(
                            buildTranscript(step1, null),
                            self.ed25519PrivateKey!!,
                        ),
                    ),
                )
                .setCapabilities(defaultCapabilities())
                .build()
            sendHandshake(connection, step2)
            val step3 = readHandshake(connection)
            require(step3.step == 3) { "Expected handshake step 3" }
            verifyPeer(step3, expectedPeer)
            val dh2 = cryptoEngine.x25519SharedSecret(
                self.x25519StaticPrivateKey!!,
                step1.ephemeralPub.toByteArray(),
            )
            val root = cryptoEngine.hkdfSha256(
                dh1 + dh2,
                ByteArray(0),
                "vmessenger-handshake".toByteArray(),
                32,
            )
            val state = ratchet.initFromRoot(root, isInitiator = false)
            ActiveSecureSession(expectedPeer, state, ratchet, connection)
        }

    suspend fun acceptResolving(
        connection: Connection,
        self: PeerIdentity,
        resolvePeer: suspend (identityPub: ByteArray, staticPub: ByteArray) -> PeerIdentity?,
    ): Result<SecureSession> = runCatching {
        val step1 = readHandshake(connection)
        require(step1.step == 1) { "Expected handshake step 1" }
        val eph = cryptoEngine.generateX25519KeyPair()
        val dh1 = cryptoEngine.x25519SharedSecret(eph.privateKey, step1.ephemeralPub.toByteArray())
        val step2 = HandshakeMessage.newBuilder()
            .setStep(2)
            .setEphemeralPub(ByteString.copyFrom(eph.publicKey))
            .setStaticPub(ByteString.copyFrom(self.x25519StaticPublicKey))
            .setIdentityPub(ByteString.copyFrom(self.ed25519PublicKey))
            .setSignature(
                ByteString.copyFrom(
                    cryptoEngine.signEd25519(
                        buildTranscript(step1, null),
                        self.ed25519PrivateKey!!,
                    ),
                ),
            )
            .setCapabilities(defaultCapabilities())
            .build()
        sendHandshake(connection, step2)
        val step3 = readHandshake(connection)
        require(step3.step == 3) { "Expected handshake step 3" }
        val identityPub = step3.identityPub.toByteArray()
        val staticPub = step3.staticPub.toByteArray()
        val peer = resolvePeer(identityPub, staticPub)
            ?: error("Unknown peer identity")
        verifyPeer(step3, peer.copy(x25519StaticPublicKey = staticPub))
        val dh2 = cryptoEngine.x25519SharedSecret(
            self.x25519StaticPrivateKey!!,
            step1.ephemeralPub.toByteArray(),
        )
        val root = cryptoEngine.hkdfSha256(
            dh1 + dh2,
            ByteArray(0),
            "vmessenger-handshake".toByteArray(),
            32,
        )
        val state = ratchet.initFromRoot(root, isInitiator = false)
        ActiveSecureSession(peer.copy(x25519StaticPublicKey = staticPub), state, ratchet, connection)
    }

    private fun verifyPeer(message: HandshakeMessage, expected: PeerIdentity) {
        val identityPub = message.identityPub.toByteArray()
        require(identityPub.contentEquals(expected.ed25519PublicKey)) {
            "Identity key mismatch"
        }
        val transcript = message.toByteArray()
        require(
            cryptoEngine.verifyEd25519(
                transcript,
                message.signature.toByteArray(),
                identityPub,
            ),
        ) { "Handshake signature invalid" }
    }

    private suspend fun sendHandshake(connection: Connection, message: HandshakeMessage) {
        val frame = Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_HANDSHAKE)
            .setBody(message.toByteString())
            .build()
        connection.write(frame.toByteArray()).getOrThrow()
    }

    private suspend fun readHandshake(connection: Connection): HandshakeMessage =
        withTimeout(15_000) {
            val bytes = connection.read().first()
            val frame = Frame.parseFrom(bytes)
            HandshakeMessage.parseFrom(frame.body)
        }

    private fun buildTranscript(step1: HandshakeMessage, step2: HandshakeMessage?): ByteArray =
        step1.toByteArray() + (step2?.toByteArray() ?: ByteArray(0))

    private fun defaultCapabilities() = Capabilities.newBuilder()
        .setProtocolMajor(1)
        .setProtocolMinor(0)
        .addFeatures("receipts")
        .addFeatures("live-location")
        .build()
}
