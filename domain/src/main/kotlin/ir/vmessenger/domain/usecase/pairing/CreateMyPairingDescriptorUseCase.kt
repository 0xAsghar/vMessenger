package ir.vmessenger.domain.usecase.pairing

import ir.vmessenger.domain.repository.PairingRepository
import javax.inject.Inject

class CreateMyPairingDescriptorUseCase @Inject constructor(
    private val pairingRepository: PairingRepository,
) {
    suspend operator fun invoke(displayLabel: String = ""): ByteArray? =
        pairingRepository.createMyDescriptor(displayLabel)
}
