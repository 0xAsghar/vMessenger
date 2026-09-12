package ir.vmessenger.domain.usecase.contact

import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The contacts hidden from the main list by a block, so the user can undo one. */
class ObserveBlockedContactsUseCase @Inject constructor(
    private val contactRepository: ContactRepository,
) {
    operator fun invoke(): Flow<List<Contact>> = contactRepository.observeBlockedContacts()
}
