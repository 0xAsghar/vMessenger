package ir.vmessenger.domain.usecase.group

import ir.vmessenger.domain.model.GroupMember
import ir.vmessenger.domain.repository.GroupRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Active members of a group, creator first; someone who left or was removed is not in it. */
class ObserveGroupMembersUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    operator fun invoke(groupId: String): Flow<List<GroupMember>> = groupRepository.observeMembers(groupId)
}
