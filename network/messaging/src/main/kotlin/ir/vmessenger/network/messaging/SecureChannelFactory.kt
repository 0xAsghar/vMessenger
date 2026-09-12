package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.ProtocolVersion
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.Capabilities
import ir.vmessenger.core.proto.wire.v1.CloseCode
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.core.proto.wire.v1.HandshakeMessage
import ir.vmessenger.network.messaging.HandshakeTranscript.Role
import ir.vmessenger.network.transport.Connection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    suspend fun seal(plaintext: ByteArray, frameType: FrameType = FrameType.FRAME_TYPE_SECURE): ByteArray
    suspend fun open(frame: ByteArray, counter: Long, frameType: FrameType = FrameType.FRAME_TYPE_SECURE): ByteArray?
    suspend fun close()
}

/**
 * A live, connection-scoped session. All writers go through [writeSealed],
 * which serializes seal+write under one mutex so concurrent callers (chat,
 * receipts, post-handshake protocol) can never race on the send counter.
 * Sessions are never persisted; they expire after [MAX_SESSION_FRAMES] frames
 * or [MAX_SESSION_AGE_MS] and the next send re-handshakes.
 */
@Suppress("LongParameterList") // peer identity, AD bindings, ratchet, transport and open time are all required
class ActiveSecureSession(
    override val peer: PeerIdentity,
    private val selfPublicKeyHash: ByteArray,
    private val peerPublicKeyHash: ByteArray,
    override val ratchetState: RatchetState,
    private val ratchet: SymmetricRatchet,
    internal val connection: Connection,
    val openedAtUnixMs: Long = System.currentTimeMillis(),
) : SecureSession {
    private val writeMutex = Mutex()

    @Volatile
    private var closed = false

    /**
     * True once [close] ran. No key material is touched afterwards: a closed
     * session neither seals nor opens, whatever the transport still delivers.
     */
    val isClosed: Boolean
        get() = closed

    /** Frames sealed plus frames opened on this session. */
    val frameCount: Long
        get() = ratchetState.sendCounter + ratchetState.recvCounter

    fun isExpired(nowUnixMs: Long = System.currentTimeMillis()): Boolean =
        frameCount >= MAX_SESSION_FRAMES || nowUnixMs - openedAtUnixMs >= MAX_SESSION_AGE_MS

    override suspend fun seal(plaintext: ByteArray, frameType: FrameType): ByteArray {
        check(!closed) { "session closed" }
        return ratchet.seal(ratchetState, plaintext, FrameAssociatedData.prefix(frameType, selfPublicKeyHash))
    }

    override suspend fun open(frame: ByteArray, counter: Long, frameType: FrameType): ByteArray? {
        if (closed) return null
        return ratchet.open(ratchetState, frame, counter, FrameAssociatedData.prefix(frameType, peerPublicKeyHash))
    }

    suspend fun writeSealed(envelope: MessageEnvelope) =
        writeSealed(envelope.toByteArray(), FrameType.FRAME_TYPE_SECURE)

    /** Seals [plaintext] and writes one `Frame{version=2, type, counter}`; throws if the write fails. */
    suspend fun writeSealed(plaintext: ByteArray, frameType: FrameType) {
        writeMutex.withLock {
            check(!closed) { "session closed" }
            val sealed = seal(plaintext, frameType)
            val frame = Frame.newBuilder()
                .setVersion(ProtocolVersion.MAJOR)
                .setType(frameType)
                .setBody(ByteString.copyFrom(sealed))
                .setCounter(ratchetState.sendCounter)
                .build()
            connection.write(frame.toByteArray()).getOrThrow()
        }
    }

    /** Best-effort unencrypted CLOSE notice; never throws. */
    suspend fun writeClose(code: CloseCode, message: String) {
        writeMutex.withLock {
            if (!closed) runCatching { connection.write(CloseFrames.encode(code, message)) }
        }
    }

    override suspend fun close() {
        closed = true
        connection.close()
        // Wipe only when no seal is in flight; a writer holding the mutex sees
        // `closed` on its next call and never seals under zeroed keys.
        if (writeMutex.tryLock()) {
            try {
                ratchetState.wipe()
            } finally {
                writeMutex.unlock()
            }
        }
    }

    companion object {
        const val MAX_SESSION_FRAMES = 65_536L
        const val MAX_SESSION_AGE_MS = 12 * 60 * 60 * 1000L
    }
}

/**
 * Handshake v2 (docs/Security.md §6): three steps, signed transcripts with
 * per-role domain tags, and a three-DH key schedule
 * `root = HKDF(dh1||dh2||dh3, salt=SHA256(T3), info="vmessenger-hs-v2-root")`.
 */
@Singleton
class SecureChannelFactory @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val ratchet: SymmetricRatchet,
) {
    suspend fun initiate(connection: Connection, self: PeerIdentity, peer: PeerIdentity): Result<SecureSession> =
        runCatching {
            val staticPrivate = requireNotNull(self.x25519StaticPrivateKey) { "missing static private key" }
            val signingPrivate = requireNotNull(self.ed25519PrivateKey) { "missing identity private key" }
            val ephemeral = cryptoEngine.generateX25519KeyPair()
            try {
                val step1 = HandshakeMessage.newBuilder()
                    .setStep(STEP_1)
                    .setEphemeralPub(ByteString.copyFrom(ephemeral.publicKey))
                    .setCapabilities(defaultCapabilities())
                    .build()
                sendHandshake(connection, step1)
                val step2 = readHandshake(connection, STEP_2)
                val resolvedPeer = matchIdentity(peer, step2.identityPub.toByteArray(), step2.staticPub.toByteArray())
                val t2 = HandshakeTranscript.extend(HandshakeTranscript.t1(step1), step2)
                verifySignature(step2, Role.RESPONDER, t2, resolvedPeer.ed25519PublicKey)
                checkPinnedStaticKey(peer, resolvedPeer)
                val step3 = HandshakeMessage.newBuilder()
                    .setStep(STEP_3)
                    .setStaticPub(ByteString.copyFrom(self.x25519StaticPublicKey))
                    .setIdentityPub(ByteString.copyFrom(self.ed25519PublicKey))
                    .setCapabilities(defaultCapabilities())
                    .build()
                val t3 = HandshakeTranscript.extend(t2, step3)
                val root = deriveRoot(
                    dh1 = sharedSecret(ephemeral.privateKey, step2.ephemeralPub),
                    dh2 = sharedSecret(ephemeral.privateKey, step2.staticPub),
                    dh3 = sharedSecret(staticPrivate, step2.ephemeralPub),
                    t3 = t3,
                )
                val signature = sign(signingPrivate, Role.INITIATOR, t3)
                sendHandshake(connection, step3.toBuilder().setSignature(ByteString.copyFrom(signature)).build())
                newSession(self, resolvedPeer, root, isInitiator = true, connection = connection)
            } finally {
                cryptoEngine.memzero(ephemeral.privateKey)
            }
        }.onFailure { notifyFailure(connection, it) }

    suspend fun accept(connection: Connection, self: PeerIdentity, expectedPeer: PeerIdentity): Result<SecureSession> =
        acceptResolving(connection, self) { identityPub, staticPub ->
            resolveExpected(expectedPeer, identityPub, staticPub)
        }

    suspend fun acceptResolving(
        connection: Connection,
        self: PeerIdentity,
        resolvePeer: suspend (identityPub: ByteArray, staticPub: ByteArray) -> PeerIdentity?,
    ): Result<SecureSession> = runCatching {
        val staticPrivate = requireNotNull(self.x25519StaticPrivateKey) { "missing static private key" }
        val signingPrivate = requireNotNull(self.ed25519PrivateKey) { "missing identity private key" }
        val step1 = readHandshake(connection, STEP_1)
        val ephemeral = cryptoEngine.generateX25519KeyPair()
        try {
            val step2 = HandshakeMessage.newBuilder()
                .setStep(STEP_2)
                .setEphemeralPub(ByteString.copyFrom(ephemeral.publicKey))
                .setStaticPub(ByteString.copyFrom(self.x25519StaticPublicKey))
                .setIdentityPub(ByteString.copyFrom(self.ed25519PublicKey))
                .setCapabilities(defaultCapabilities())
                .build()
            val t2 = HandshakeTranscript.extend(HandshakeTranscript.t1(step1), step2)
            val signature = sign(signingPrivate, Role.RESPONDER, t2)
            sendHandshake(connection, step2.toBuilder().setSignature(ByteString.copyFrom(signature)).build())
            val step3 = readHandshake(connection, STEP_3)
            val identityPub = step3.identityPub.toByteArray()
            val staticPub = step3.staticPub.toByteArray()
            val t3 = HandshakeTranscript.extend(t2, step3)
            verifySignature(step3, Role.INITIATOR, t3, identityPub)
            val fullHash = cryptoEngine.sha256(identityPub)
            val peer = resolvePeer(identityPub, staticPub)
                ?: error("Unknown peer identity (hash=${IdentityHashMatcher.hashPrefixHex(fullHash)})")
            val resolvedPeer = peer.copy(
                identityHash = fullHash,
                ed25519PublicKey = identityPub,
                x25519StaticPublicKey = staticPub,
            )
            val root = deriveRoot(
                dh1 = sharedSecret(ephemeral.privateKey, step1.ephemeralPub),
                dh2 = sharedSecret(staticPrivate, step1.ephemeralPub),
                dh3 = sharedSecret(ephemeral.privateKey, step3.staticPub),
                t3 = t3,
            )
            newSession(self, resolvedPeer, root, isInitiator = false, connection = connection)
        } finally {
            cryptoEngine.memzero(ephemeral.privateKey)
        }
    }.onFailure { notifyFailure(connection, it) }

    /**
     * Matches the keys a peer presented against what we expected: a hash-only
     * (placeholder) contact must match by identity hash, a known contact by
     * identity key, and (after the signature is verified) a pinned X25519
     * static key may never silently change.
     */
    private fun resolveExpected(expected: PeerIdentity, identityPub: ByteArray, staticPub: ByteArray): PeerIdentity {
        val resolved = matchIdentity(expected, identityPub, staticPub)
        checkPinnedStaticKey(expected, resolved)
        return resolved
    }

    private fun matchIdentity(expected: PeerIdentity, identityPub: ByteArray, staticPub: ByteArray): PeerIdentity {
        val fullHash = cryptoEngine.sha256(identityPub)
        if (IdentityHashMatcher.isPlaceholderPublicKey(expected.ed25519PublicKey)) {
            require(IdentityHashMatcher.matches(expected.identityHash, fullHash)) { "Identity hash mismatch" }
        } else {
            require(identityPub.contentEquals(expected.ed25519PublicKey)) { "Identity key mismatch" }
        }
        return expected.copy(
            identityHash = fullHash,
            ed25519PublicKey = identityPub,
            x25519StaticPublicKey = staticPub,
        )
    }

    /** TOFU: a placeholder (all-zero) pin is filled by the caller; a real pin must match exactly. */
    private fun checkPinnedStaticKey(expected: PeerIdentity, presented: PeerIdentity) {
        val pinned = expected.x25519StaticPublicKey
        if (IdentityHashMatcher.isPlaceholderPublicKey(pinned)) return
        if (pinned.contentEquals(presented.x25519StaticPublicKey)) return
        AppLogger.warn(
            "Messaging",
            "key change pending peer=${IdentityHashMatcher.hashPrefixHex(presented.identityHash)} (rejected)",
        )
        throw PeerKeyChangedException(
            identityHash = presented.identityHash,
            newStaticKey = presented.x25519StaticPublicKey,
        )
    }

    private fun verifySignature(
        message: HandshakeMessage,
        role: Role,
        transcript: ByteArray,
        expectedEd25519PublicKey: ByteArray,
    ) {
        require(message.identityPub.toByteArray().contentEquals(expectedEd25519PublicKey)) { "Identity key mismatch" }
        require(
            cryptoEngine.verifyEd25519(
                HandshakeTranscript.signatureInput(role, transcript),
                message.signature.toByteArray(),
                expectedEd25519PublicKey,
            ),
        ) { "Handshake signature invalid" }
    }

    private fun sign(privateKey: ByteArray, role: Role, transcript: ByteArray): ByteArray =
        cryptoEngine.signEd25519(HandshakeTranscript.signatureInput(role, transcript), privateKey)

    private fun sharedSecret(privateKey: ByteArray, publicKey: ByteString): ByteArray {
        val public = publicKey.toByteArray()
        require(public.size == X25519_KEY_BYTES) { "Invalid X25519 public key length" }
        val shared = cryptoEngine.x25519SharedSecret(privateKey, public)
        if (shared.all { it == 0.toByte() }) {
            cryptoEngine.memzero(shared)
            error("X25519 low-order point rejected")
        }
        return shared
    }

    private fun deriveRoot(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, t3: ByteArray): ByteArray {
        val ikm = dh1 + dh2 + dh3
        val root = cryptoEngine.hkdfSha256(
            ikm,
            cryptoEngine.sha256(t3),
            HandshakeTranscript.ROOT_INFO.toByteArray(Charsets.UTF_8),
            ROOT_KEY_BYTES,
        )
        listOf(dh1, dh2, dh3, ikm).forEach(cryptoEngine::memzero)
        return root
    }

    private fun newSession(
        self: PeerIdentity,
        peer: PeerIdentity,
        root: ByteArray,
        isInitiator: Boolean,
        connection: Connection,
    ): ActiveSecureSession {
        val state = ratchet.initFromRoot(root, isInitiator)
        cryptoEngine.memzero(root)
        AppLogger.info(
            "Messaging",
            "handshake v2 ok peer=${IdentityHashMatcher.hashPrefixHex(peer.identityHash)} " +
                "role=${if (isInitiator) "initiator" else "responder"} dh=3",
        )
        return ActiveSecureSession(
            peer = peer,
            selfPublicKeyHash = cryptoEngine.sha256(self.ed25519PublicKey),
            peerPublicKeyHash = cryptoEngine.sha256(peer.ed25519PublicKey),
            ratchetState = state,
            ratchet = ratchet,
            connection = connection,
        )
    }

    private suspend fun sendHandshake(connection: Connection, message: HandshakeMessage) {
        val bytes = Frame.newBuilder()
            .setVersion(ProtocolVersion.MAJOR)
            .setType(FrameType.FRAME_TYPE_HANDSHAKE)
            .setBody(message.toByteString())
            .build()
            .toByteArray()
        check(bytes.size <= MAX_HANDSHAKE_FRAME_BYTES) { "handshake frame too large (${bytes.size} B)" }
        connection.write(bytes).getOrThrow()
    }

    /**
     * Reads one handshake frame and validates, in order: size, `Frame.version`,
     * frame type (a CLOSE is surfaced as its own error), step number and the
     * advertised `protocol_major`. A version mismatch answers with
     * `CLOSE{VERSION_MISMATCH}` and throws [ProtocolVersionException].
     */
    private suspend fun readHandshake(connection: Connection, expectedStep: Int): HandshakeMessage {
        val bytes = withTimeout(HANDSHAKE_TIMEOUT_MS) { connection.read().first() }
        require(bytes.size <= MAX_HANDSHAKE_FRAME_BYTES) { "handshake frame too large (${bytes.size} B)" }
        val frame = Frame.parseFrom(bytes)
        if (frame.version != ProtocolVersion.MAJOR) rejectVersion(connection, frame.version)
        if (frame.type == FrameType.FRAME_TYPE_CLOSE) rejectPeerClose(frame)
        require(frame.type == FrameType.FRAME_TYPE_HANDSHAKE) { "Expected handshake frame, got ${frame.type}" }
        val message = HandshakeMessage.parseFrom(frame.body)
        require(message.step == expectedStep) { "Expected handshake step $expectedStep" }
        if (message.capabilities.protocolMajor != ProtocolVersion.MAJOR) {
            rejectVersion(connection, message.capabilities.protocolMajor)
        }
        return message
    }

    private suspend fun rejectVersion(connection: Connection, peerMajor: Int): Nothing {
        runCatching {
            connection.write(
                CloseFrames.encode(CloseCode.CLOSE_CODE_VERSION_MISMATCH, "protocol major $peerMajor unsupported"),
            )
        }
        AppLogger.warn(
            "Messaging",
            "handshake rejected: peer protocol major=$peerMajor (need ${ProtocolVersion.MAJOR})",
        )
        throw ProtocolVersionException(peerMajor)
    }

    private fun rejectPeerClose(frame: Frame): Nothing {
        val close = CloseFrames.decode(frame)
        if (close.code == CloseCode.CLOSE_CODE_VERSION_MISMATCH.number) {
            AppLogger.warn("Messaging", "handshake rejected by peer: ${CloseFrames.describe(close)}")
            throw ProtocolVersionException(close.supportedMajor, "peer requires protocol major ${close.supportedMajor}")
        }
        error("Peer closed handshake: ${CloseFrames.describe(close)}")
    }

    /** Best-effort CLOSE so the peer logs a reason instead of a bare EOF. */
    private suspend fun notifyFailure(connection: Connection, cause: Throwable) {
        if (cause is ProtocolVersionException) return
        val authFailure = cause is PeerKeyChangedException ||
            cause.message?.let { it.startsWith("Handshake signature") || it.startsWith("Identity") } == true
        val code = if (authFailure) CloseCode.CLOSE_CODE_AUTH_FAILED else CloseCode.CLOSE_CODE_REJECTED
        runCatching { connection.write(CloseFrames.encode(code, "handshake failed")) }
    }

    private fun defaultCapabilities() = Capabilities.newBuilder()
        .setProtocolMajor(ProtocolVersion.MAJOR)
        .setProtocolMinor(ProtocolVersion.MINOR)
        .addFeatures("receipts")
        .addFeatures("live-location")
        .build()

    companion object {
        const val MAX_HANDSHAKE_FRAME_BYTES = 4096
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val STEP_1 = 1
        private const val STEP_2 = 2
        private const val STEP_3 = 3
        private const val X25519_KEY_BYTES = 32
        private const val ROOT_KEY_BYTES = 32
    }
}
