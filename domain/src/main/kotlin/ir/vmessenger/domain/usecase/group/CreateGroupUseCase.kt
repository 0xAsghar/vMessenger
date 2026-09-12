package ir.vmessenger.domain.usecase.group

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.GroupRepository
import javax.inject.Inject

/** Creates a group and returns the id of its conversation, ready to be opened. */
class CreateGroupUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    suspend operator fun invoke(name: String, memberContactIds: List<String>): AppResult<String> =
        groupRepository.createGroup(name, memberContactIds)
}
