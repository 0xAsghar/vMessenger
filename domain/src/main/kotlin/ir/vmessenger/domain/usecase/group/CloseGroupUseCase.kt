package ir.vmessenger.domain.usecase.group

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.GroupRepository
import javax.inject.Inject

/** Creator-only: closes the group for everyone, leaving the history readable. */
class CloseGroupUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    suspend operator fun invoke(groupId: String): AppResult<Unit> = groupRepository.closeGroup(groupId)
}
