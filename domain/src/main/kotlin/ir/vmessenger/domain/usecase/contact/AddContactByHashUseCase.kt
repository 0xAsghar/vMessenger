package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

class AddContactByHashUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(userHash: String, alias: String? = null): AppResult<Contact> =
        contactRepository.addContactByUserHash(userHash, alias)
}
