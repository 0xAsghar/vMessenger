package ir.vmessenger.domain.usecase.group

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.GroupRepository
import javax.inject.Inject

/** Announces our departure, then makes the local copy read-only. The creator closes instead. */
class LeaveGroupUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    suspend operator fun invoke(groupId: String): AppResult<Unit> = groupRepository.leaveGroup(groupId)
}
