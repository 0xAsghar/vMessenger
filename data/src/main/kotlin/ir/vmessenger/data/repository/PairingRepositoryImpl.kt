package ir.vmessenger.data.repository

import ir.vmessenger.core.crypto.pairing.PairingDescriptorCodec
import ir.vmessenger.core.proto.wire.v1.PairingDescriptor
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.repository.PairingRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val codec: PairingDescriptorCodec,
) : PairingRepository {
    override suspend fun createMyDescriptor(displayLabel: String): ByteArray? {
        val identity = identityRepository.getIdentity()
        val privateKey = if (identity != null) identityRepository.getEd25519PrivateKey() else null
        if (identity == null || privateKey == null) return null
        return codec.createSigned(
            ed25519PublicKey = identity.ed25519PublicKey,
            userHash = identity.userHash,
            displayLabel = displayLabel,
            privateKey = privateKey,
        ).toByteArray()
    }

    override fun encodeDescriptor(descriptorBytes: ByteArray): String =
        codec.encodeBase64(PairingDescriptor.parseFrom(descriptorBytes))

    override fun decodeDescriptor(payload: String): ByteArray? =
        codec.decodeBase64(payload)?.toByteArray()

    override fun verifyDescriptor(descriptorBytes: ByteArray): Boolean =
        codec.verify(PairingDescriptor.parseFrom(descriptorBytes))
}
