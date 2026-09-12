package ir.vmessenger.domain.usecase.group

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.GroupRepository
import javax.inject.Inject

/** Creator-only: removes a member; they are told and their copy becomes read-only. */
class RemoveGroupMemberUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    suspend operator fun invoke(groupId: String, identityHash: String): AppResult<Unit> =
        groupRepository.removeMember(groupId, identityHash)
}
