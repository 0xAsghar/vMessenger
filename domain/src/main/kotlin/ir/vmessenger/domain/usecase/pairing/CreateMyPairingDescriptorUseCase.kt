package ir.vmessenger.domain.usecase.pairing

import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.repository.PairingRepository
import javax.inject.Inject

class CreateMyPairingDescriptorUseCase @Inject constructor(
    private val pairingRepository: PairingRepository,
    private val identityRepository: IdentityRepository,
) {
    suspend operator fun invoke(): ByteArray? {
        val identity = identityRepository.getIdentity() ?: return null
        val label = identity.displayName.ifBlank { identity.userHash }
        return pairingRepository.createMyDescriptor(label)
    }
}
