package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

class AddContactByHashUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
    private val sendContactRequestUseCase: SendContactRequestUseCase,
) {
    suspend operator fun invoke(userHash: String, alias: String? = null): AppResult<Contact> =
        when (val result = contactRepository.addContactByUserHash(userHash, alias)) {
            is AppResult.Success -> {
                sendContactRequestUseCase(result.data)
                result
            }
            is AppResult.Error -> result
        }
}
