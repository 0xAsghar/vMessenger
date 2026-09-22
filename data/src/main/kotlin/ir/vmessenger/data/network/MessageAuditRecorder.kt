package ir.vmessenger.data.network

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.dao.MessageEditHistoryDao
import ir.vmessenger.core.database.entity.MessageEditHistoryEntity
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.MessageRevisionKind
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures what a group message said, just before it is edited or withdrawn.
 *
 * This is the one place that decides whether a capture happens, and it says no in every case but
 * one: the message belongs to a group, that group's creator switched retention on, and there is
 * something to keep. A 1:1 conversation has no `groupId`, so [capture] returns false for it before
 * looking at anything else — there is no creator to author such a policy and no admin to read it,
 * and a 1:1 delete must keep erasing.
 *
 * It also reports whether it captured, because the attachment file's fate depends on it: with
 * retention on the file stays so the captured row still resolves, and with it off the file goes as
 * it always has.
 */
@Singleton
class MessageAuditRecorder @Inject constructor(
    private val groupDao: GroupDao,
    private val historyDao: MessageEditHistoryDao,
) {
    /**
     * Records [target] as it stands now. Returns true when something was written — which is also
     * the answer to "must the attachment file be kept?".
     */
    suspend fun capture(groupId: String?, target: MessageEntity, revision: MessageRevisionKind): Boolean {
        // A 1:1 thread has no group id, so it fails the first clause and never reaches the query:
        // there is no creator to author such a policy and no admin to read it.
        val retaining = groupId != null &&
            runCatching { groupDao.getById(groupId)?.auditRetention }.getOrNull() == true
        val hasContent = target.body != null || target.caption != null || target.attachmentPath != null
        if (groupId == null || !retaining || !hasContent) return false
        val now = System.currentTimeMillis()
        historyDao.insert(
            MessageEditHistoryEntity(
                messageId = target.messageId,
                groupId = groupId,
                authorIdentityHash = target.senderIdentityHash,
                revision = revision,
                body = target.body,
                caption = target.caption,
                attachmentName = target.attachmentName,
                attachmentPath = target.attachmentPath,
                capturedAtUnixMs = now,
            ),
        )
        // Opportunistic and bounded. Retention is a review window, not an archive, and a group that
        // keeps everything forever is a group whose members were promised less than they got.
        historyDao.purgeOlderThan(now - RETENTION_MS)
        AppLogger.info(TAG, "captured a $revision under audit retention group=$groupId")
        return true
    }

    private companion object {
        const val TAG = "Messaging"

        /** Ninety days. Long enough to review an incident, short enough not to be a dossier. */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(90)
    }
}
