package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

/**
 * Marks that the user has compared this contact's safety number out of band, or clears that
 * mark. It is a local annotation only — nothing about the session or the pinned key changes.
 */
class SetContactVerifiedUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(contactId: String, verified: Boolean): AppResult<Unit> =
        contactRepository.setVerified(contactId, verified)
}
