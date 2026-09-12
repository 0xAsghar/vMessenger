package ir.vmessenger.domain.usecase.group

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.GroupRepository
import javax.inject.Inject

/** Creator-only: renames the group and announces the change. */
class UpdateGroupNameUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    suspend operator fun invoke(groupId: String, name: String): AppResult<Unit> =
        groupRepository.renameGroup(groupId, name)
}
