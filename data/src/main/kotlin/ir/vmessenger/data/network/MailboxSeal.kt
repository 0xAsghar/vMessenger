package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.network.Canonical
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.app.v1.MailboxInner
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import javax.inject.Inject
import javax.inject.Singleton

/** Plaintext recovered from a sealed mailbox blob whose sender signature verified. */
class OpenedMailboxInner(
    val senderIdentityPub: ByteArray,
    val envelope: MessageEnvelope,
)

/**
 * Sealed-box layer of store-and-forward: a mailbox peer only ever holds
 * `crypto_box_seal(MailboxInner, recipient x25519 static key)`, and the inner
 * envelope carries an Ed25519 signature by the sender's identity key over
 * `"vmessenger-mailbox-v2" || lp(recipient_hash) || SHA256(envelope)` so a blob
 * cannot be re-addressed or forged by whoever stored it. Blob ids are
 * content-addressed from the sealed bytes. Limitation (documented): mailbox
 * delivery has no forward secrecy, unlike the ratcheted live session.
 */
@Singleton
class MailboxSeal @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    /**
     * Seals [envelope] for the recipient. Takes ownership of [senderEd25519Private]
     * and zeroizes it before returning.
     */
    @Suppress("LongParameterList") // one argument per party in the transcript
    fun seal(
        envelope: MessageEnvelope,
        recipientIdentityHash: ByteArray,
        recipientStaticPublic: ByteArray,
        senderIdentityPub: ByteArray,
        senderEd25519Private: ByteArray,
    ): ByteArray {
        val envelopeBytes = envelope.toByteArray()
        val signature = try {
            cryptoEngine.signEd25519(transcript(recipientIdentityHash, envelopeBytes), senderEd25519Private)
        } finally {
            cryptoEngine.memzero(senderEd25519Private)
        }
        val inner = MailboxInner.newBuilder()
            .setSenderIdentityPub(ByteString.copyFrom(senderIdentityPub))
            .setEnvelope(ByteString.copyFrom(envelopeBytes))
            .setSignature(ByteString.copyFrom(signature))
            .build()
            .toByteArray()
        return cryptoEngine.sealedBoxSeal(inner, recipientStaticPublic)
    }

    /**
     * Opens [sealedPayload] with the recipient's static pair and verifies the
     * sender signature against every hash in [recipientHashCandidates] (the
     * recipient's own hash plus the form the sender addressed). Null when the
     * box does not open, the inner message is malformed, or no candidate verifies.
     */
    fun open(
        sealedPayload: ByteArray,
        recipientStaticPublic: ByteArray,
        recipientStaticPrivate: ByteArray,
        recipientHashCandidates: List<ByteArray>,
    ): OpenedMailboxInner? {
        val plain = cryptoEngine.sealedBoxOpen(sealedPayload, recipientStaticPublic, recipientStaticPrivate)
            ?: return null
        val inner = runCatching { MailboxInner.parseFrom(plain) }.getOrNull()
        cryptoEngine.memzero(plain)
        return inner?.takeIf { signatureVerifies(it, recipientHashCandidates) }?.let(::toOpened)
    }

    /** Content-addressed id: `hex(SHA256(sealedPayload))[0..32)`; sender-chosen ids are ignored. */
    fun blobId(sealedPayload: ByteArray): String =
        cryptoEngine.sha256(sealedPayload).joinToString("") { "%02x".format(it) }.take(BLOB_ID_CHARS)

    /** Best-effort wipe for key material handed to callers of [open]. */
    fun wipe(bytes: ByteArray) = cryptoEngine.memzero(bytes)

    /** Signed bytes: domain tag, length-prefixed recipient hash, digest of the inner envelope. */
    fun transcript(recipientIdentityHash: ByteArray, envelopeBytes: ByteArray): ByteArray =
        DOMAIN + Canonical.lp(recipientIdentityHash) + cryptoEngine.sha256(envelopeBytes)

    private fun signatureVerifies(inner: MailboxInner, candidates: List<ByteArray>): Boolean {
        val senderPub = inner.senderIdentityPub.toByteArray()
        val usableKey = senderPub.size == ED25519_PUBLIC_BYTES && !IdentityHashMatcher.isPlaceholderPublicKey(senderPub)
        if (!usableKey) return false
        val signature = inner.signature.toByteArray()
        val envelopeBytes = inner.envelope.toByteArray()
        return candidates.any { cryptoEngine.verifyEd25519(transcript(it, envelopeBytes), signature, senderPub) }
    }

    private fun toOpened(inner: MailboxInner): OpenedMailboxInner? =
        runCatching { MessageEnvelope.parseFrom(inner.envelope) }.getOrNull()
            ?.let { OpenedMailboxInner(inner.senderIdentityPub.toByteArray(), it) }

    companion object {
        /** Value of `MailboxBlob.seal_version` for this layout. */
        const val SEAL_VERSION = 2
        private const val BLOB_ID_CHARS = 32
        private const val ED25519_PUBLIC_BYTES = 32
        private val DOMAIN = "vmessenger-mailbox-v2".toByteArray(Charsets.UTF_8)
    }
}
