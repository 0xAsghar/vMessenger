package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

class BlockContactUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(id: String, blocked: Boolean) =
        contactRepository.blockContact(id, blocked)
}
