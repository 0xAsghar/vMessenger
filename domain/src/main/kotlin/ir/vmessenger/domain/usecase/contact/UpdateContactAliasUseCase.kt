package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.domain.repository.ContactRepository
import javax.inject.Inject

class UpdateContactAliasUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    suspend operator fun invoke(id: String, alias: String) =
        contactRepository.updateContactAlias(id, alias)
}
