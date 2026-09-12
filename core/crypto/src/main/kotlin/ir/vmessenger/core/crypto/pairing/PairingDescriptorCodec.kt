package ir.vmessenger.core.crypto.pairing

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.network.Canonical.lp
import ir.vmessenger.core.common.network.Canonical.lpUtf8
import ir.vmessenger.core.common.network.Canonical.u32be
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.wire.v1.PairingDescriptor
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs and verifies the QR pairing descriptor. Format v2: the signature covers
 * `"vmessenger-pairing-v2" || lp(identity_pub) || lpUtf8(user_hash) || lpUtf8(display_label) || u32be(version)`
 * rather than the protobuf bytes, and [verify] accepts only `version == 2`.
 */
@Singleton
class PairingDescriptorCodec @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    fun createSigned(
        ed25519PublicKey: ByteArray,
        userHash: String,
        displayLabel: String,
        privateKey: ByteArray,
    ): PairingDescriptor {
        val unsigned = PairingDescriptor.newBuilder()
            .setIdentityPub(ByteString.copyFrom(ed25519PublicKey))
            .setUserHash(userHash)
            .setDisplayLabel(displayLabel)
            .setVersion(VERSION)
            .build()
        val signature = cryptoEngine.signEd25519(buildTranscript(unsigned), privateKey)
        return unsigned.toBuilder().setSignature(ByteString.copyFrom(signature)).build()
    }

    fun verify(descriptor: PairingDescriptor): Boolean {
        val identityPub = descriptor.identityPub.toByteArray()
        val wellFormed = descriptor.version == VERSION &&
            !descriptor.signature.isEmpty &&
            identityPub.size == IDENTITY_PUB_BYTES
        if (!wellFormed) return false
        val hash = UserHashEncoder.identityHashFromPublicKey(identityPub)
        val expectedHash = UserHashEncoder.decode(descriptor.userHash)
        val hashValid = expectedHash != null && hash.copyOf(expectedHash.size).contentEquals(expectedHash)
        return hashValid && cryptoEngine.verifyEd25519(
            buildTranscript(descriptor),
            descriptor.signature.toByteArray(),
            identityPub,
        )
    }

    /** Signed bytes; the `signature` field is never part of the transcript. */
    fun buildTranscript(descriptor: PairingDescriptor): ByteArray =
        TAG_V2 +
            lp(descriptor.identityPub.toByteArray()) +
            lpUtf8(descriptor.userHash) +
            lpUtf8(descriptor.displayLabel) +
            u32be(descriptor.version)

    fun encodeBase64(descriptor: PairingDescriptor): String =
        Base64.getEncoder().encodeToString(descriptor.toByteArray())

    fun decodeBase64(value: String): PairingDescriptor? = runCatching {
        PairingDescriptor.parseFrom(Base64.getDecoder().decode(value.trim()))
    }.getOrNull()

    companion object {
        const val VERSION = 2
        private const val IDENTITY_PUB_BYTES = 32
        private val TAG_V2 = "vmessenger-pairing-v2".toByteArray(Charsets.UTF_8)
    }
}
