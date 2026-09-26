package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.MessageEditHistoryDao
import ir.vmessenger.core.database.entity.MessageEditHistoryEntity
import ir.vmessenger.data.attachment.AttachmentFileStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What group message review kept, read and erased in one place for the creator's device and every
 * member's alike. Each device captures under the policy, so each must erase when it is switched off;
 * before 2.0.2 only the creator's did, and members' captures — and the attachment files kept for
 * them — stayed.
 */
@Singleton
class GroupAuditHistory @Inject constructor(
    private val historyDao: MessageEditHistoryDao,
    private val attachmentFiles: AttachmentFileStore,
) {
    fun observe(groupId: String, limit: Int): Flow<List<MessageEditHistoryEntity>> =
        historyDao.observeForGroup(groupId, limit)

    /**
     * Erases [groupId]'s captures and the attachment files only they still point at. A capture of an
     * edit shares its file with the live message, and that file stays.
     */
    suspend fun erase(groupId: String) {
        val orphaned = historyDao.orphanedAttachmentPaths(groupId)
        historyDao.deleteForGroup(groupId)
        orphaned.forEach { attachmentFiles.delete(it) }
        AppLogger.info("Messaging", "erased review captures group=$groupId files=${orphaned.size}")
    }

    /**
     * Erases captures of groups whose review is off or that this device no longer holds. Under the
     * current code there are none; a member's device upgraded from 2.0.1 may still hold them.
     */
    suspend fun eraseLeftovers() {
        historyDao.groupsHeldWithoutReview().forEach { erase(it) }
    }
}
