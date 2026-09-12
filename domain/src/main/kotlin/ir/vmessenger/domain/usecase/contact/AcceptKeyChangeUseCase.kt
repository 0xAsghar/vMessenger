package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

/**
 * Pins a contact's changed X25519 static key after the user has re-verified it
 * (see [ir.vmessenger.domain.model.Contact.keyChangePending]).
 */
class AcceptKeyChangeUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> = contactRepository.acceptKeyChange(id)
}
