package ir.vmessenger.domain.usecase.group

import ir.vmessenger.domain.model.GroupAuditEntry
import ir.vmessenger.domain.repository.GroupRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * The revisions this device captured for a group, newest first.
 *
 * Whether the screen should be reachable at all is decided by the group's own state — retention on,
 * and this device's member row a creator or an admin — not here.
 */
class ObserveGroupAuditUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    operator fun invoke(groupId: String): Flow<List<GroupAuditEntry>> =
        groupRepository.observeAuditEntries(groupId)
}
