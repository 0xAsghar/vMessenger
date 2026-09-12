package ir.vmessenger.domain.usecase.group

import ir.vmessenger.domain.model.Group
import ir.vmessenger.domain.repository.GroupRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The group behind a conversation; emits null once it is erased. */
class ObserveGroupUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    operator fun invoke(groupId: String): Flow<Group?> = groupRepository.observeGroup(groupId)
}
