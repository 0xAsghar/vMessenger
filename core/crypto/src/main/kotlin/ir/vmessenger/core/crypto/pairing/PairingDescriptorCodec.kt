package ir.vmessenger.core.crypto.pairing

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.wire.v1.PairingDescriptor
import javax.inject.Inject
import javax.inject.Singleton

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
            .setVersion(VERSION.toInt())
            .build()
        val signature = cryptoEngine.signEd25519(unsigned.toByteArray(), privateKey)
        return unsigned.toBuilder().setSignature(ByteString.copyFrom(signature)).build()
    }

    fun verify(descriptor: PairingDescriptor): Boolean {
        if (descriptor.signature.isEmpty) return false
        val identityPub = descriptor.identityPub.toByteArray()
        val hash = UserHashEncoder.identityHashFromPublicKey(identityPub)
        val expectedHash = UserHashEncoder.decode(descriptor.userHash)
        val hashValid = expectedHash != null && hash.copyOf(16).contentEquals(expectedHash)
        val unsigned = descriptor.toBuilder().clearSignature().build()
        return hashValid && cryptoEngine.verifyEd25519(
            unsigned.toByteArray(),
            descriptor.signature.toByteArray(),
            identityPub,
        )
    }

    fun encodeBase64(descriptor: PairingDescriptor): String =
        android.util.Base64.encodeToString(descriptor.toByteArray(), android.util.Base64.NO_WRAP)

    fun decodeBase64(value: String): PairingDescriptor? = runCatching {
        PairingDescriptor.parseFrom(android.util.Base64.decode(value, android.util.Base64.NO_WRAP))
    }.getOrNull()

    companion object {
        const val VERSION = 1
    }
}
