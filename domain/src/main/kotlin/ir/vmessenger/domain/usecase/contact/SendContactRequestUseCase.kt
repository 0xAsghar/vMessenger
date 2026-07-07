package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRequestSender
import javax.inject.Inject

class SendContactRequestUseCase @Inject constructor(
    private val contactRequestSender: ContactRequestSender,
) {
    suspend operator fun invoke(contact: Contact): AppResult<Unit> =
        contactRequestSender.sendRequest(contact)
}
