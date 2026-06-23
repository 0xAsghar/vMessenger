package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

class DeleteContactUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(id: String) = contactRepository.deleteContact(id)
}
