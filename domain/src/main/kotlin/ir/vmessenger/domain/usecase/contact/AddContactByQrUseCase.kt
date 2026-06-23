package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

class AddContactByQrUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(descriptorBytes: ByteArray, alias: String? = null): AppResult<Contact> =
        contactRepository.addContactByDescriptor(descriptorBytes, alias)
}
