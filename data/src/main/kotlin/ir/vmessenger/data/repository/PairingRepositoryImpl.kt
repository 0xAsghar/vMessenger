package ir.vmessenger.data.repository

import ir.vmessenger.core.crypto.pairing.PairingDescriptorCodec
import ir.vmessenger.core.proto.wire.v1.PairingDescriptor
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.repository.PairingRepository
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val codec: PairingDescriptorCodec,
) : PairingRepository {
    private val descriptorCache = AtomicReference<CachedDescriptor?>(null)

    override suspend fun createMyDescriptor(displayLabel: String): ByteArray? {
        val identity = identityRepository.getIdentity()
        val privateKey = if (identity != null) identityRepository.getEd25519PrivateKey() else null
        if (identity == null || privateKey == null) return null

        descriptorCache.get()?.let { cached ->
            if (cached.userHash == identity.userHash && cached.displayLabel == displayLabel) {
                return cached.bytes
            }
        }

        val bytes = codec.createSigned(
            ed25519PublicKey = identity.ed25519PublicKey,
            userHash = identity.userHash,
            displayLabel = displayLabel,
            privateKey = privateKey,
        ).toByteArray()
        descriptorCache.set(CachedDescriptor(identity.userHash, displayLabel, bytes))
        return bytes
    }

    override fun encodeDescriptor(descriptorBytes: ByteArray): String =
        codec.encodeBase64(PairingDescriptor.parseFrom(descriptorBytes))

    override fun decodeDescriptor(payload: String): ByteArray? =
        codec.decodeBase64(payload)?.toByteArray()

    override fun verifyDescriptor(descriptorBytes: ByteArray): Boolean =
        codec.verify(PairingDescriptor.parseFrom(descriptorBytes))

    private data class CachedDescriptor(
        val userHash: String,
        val displayLabel: String,
        val bytes: ByteArray,
    )
}
