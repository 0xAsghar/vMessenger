package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

/**
 * QR pairing carries the peer's full identity, so the scanner trusts and
 * approves them immediately. We still send the peer a contact request so they
 * learn about us and add us back — otherwise the pairing is one-sided and the
 * scanned device never knows it was added. Delivery is retried by
 * ContactRequestRetryWorker until the peer responds.
 */
class AddContactByQrUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
    private val sendContactRequestUseCase: SendContactRequestUseCase,
) {
    suspend operator fun invoke(descriptorBytes: ByteArray, alias: String? = null): AppResult<Contact> =
        when (val result = contactRepository.addContactByDescriptor(descriptorBytes, alias)) {
            is AppResult.Success -> {
                sendContactRequestUseCase(result.data)
                result
            }
            is AppResult.Error -> result
        }
}
