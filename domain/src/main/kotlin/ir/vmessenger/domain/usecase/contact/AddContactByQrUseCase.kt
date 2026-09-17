package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

/**
 * QR pairing carries the peer's full, signed identity, so the scanner learns
 * exactly who they are — but not that they want us. The contact is stored as
 * pending, like a hash add, and a contact request goes to the peer; it becomes
 * approved when they accept (or at once, if they have already added us).
 * Delivery is retried by ContactRequestRetryWorker until the peer responds, so
 * scanning someone who is offline right now still reaches them later.
 */
class AddContactByQrUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
    private val sendContactRequestUseCase: SendContactRequestUseCase,
) {
    suspend operator fun invoke(descriptorBytes: ByteArray, alias: String? = null): AppResult<Contact> =
        when (val result = contactRepository.addContactByDescriptor(descriptorBytes, alias)) {
            is AppResult.Success -> {
                sendContactRequestUseCase.startInBackground(result.data)
                result
            }
            is AppResult.Error -> result
        }
}
