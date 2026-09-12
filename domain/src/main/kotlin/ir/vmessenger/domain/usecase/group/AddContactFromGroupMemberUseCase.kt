package ir.vmessenger.domain.usecase.group

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.GroupRepository
import javax.inject.Inject

/**
 * Asks a group member to become a contact. Being in the same group is not consent
 * to a private chat, so this sends a normal contact request rather than adding
 * them outright.
 */
class AddContactFromGroupMemberUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    suspend operator fun invoke(groupId: String, identityHash: String): AppResult<Unit> =
        groupRepository.addMemberAsContact(groupId, identityHash)
}
