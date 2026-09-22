package ir.vmessenger.domain.usecase.group

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.GroupRepository
import javax.inject.Inject

/**
 * Creator-only: switches admin review of edited and deleted messages on or off.
 *
 * Turning it on is disclosed to every member — a system line in the conversation and a banner on
 * the group screen — because it takes away something they had. Turning it off erases what was kept.
 */
class SetGroupAuditRetentionUseCase @Inject constructor(
    private val groupRepository: GroupRepository,
) {
    suspend operator fun invoke(groupId: String, enabled: Boolean): AppResult<Unit> =
        groupRepository.setAuditRetention(groupId, enabled)
}
