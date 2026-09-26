package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ir.vmessenger.core.database.entity.MessageEditHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Captured revisions of group messages.
 *
 * Append-only by design: there is no update, because a record of what was said cannot be sensibly
 * amended. [deleteForGroup] exists for the one legitimate erasure — the creator switching retention
 * off, which should not leave a stockpile behind — and [purgeOlderThan] for the retention bound.
 */
@Dao
interface MessageEditHistoryDao {
    @Insert
    suspend fun insert(entity: MessageEditHistoryEntity)

    /** Newest first, bounded: an audit response must not be an unbounded dump of a group's history. */
    @Query(
        "SELECT * FROM message_edit_history WHERE groupId = :groupId " +
            "ORDER BY capturedAtUnixMs DESC LIMIT :limit",
    )
    suspend fun forGroup(groupId: String, limit: Int): List<MessageEditHistoryEntity>

    /** Bounded like [forGroup]: a review screen is a window, not a dump of everything kept. */
    @Query(
        "SELECT * FROM message_edit_history WHERE groupId = :groupId " +
            "ORDER BY capturedAtUnixMs DESC LIMIT :limit",
    )
    fun observeForGroup(groupId: String, limit: Int): Flow<List<MessageEditHistoryEntity>>

    @Query("SELECT * FROM message_edit_history WHERE messageId = :messageId ORDER BY capturedAtUnixMs ASC")
    suspend fun forMessage(messageId: String): List<MessageEditHistoryEntity>

    @Query("SELECT COUNT(*) FROM message_edit_history WHERE groupId = :groupId")
    suspend fun countForGroup(groupId: String): Int

    /**
     * The attachment files [groupId]'s captures point at that no message row does: what erasing the
     * captures leaves unreferenced. A capture of an edit shares the live message's file.
     */
    @Query(
        "SELECT DISTINCT h.attachmentPath FROM message_edit_history h " +
            "WHERE h.groupId = :groupId AND h.attachmentPath IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM message m WHERE m.attachmentPath = h.attachmentPath)",
    )
    suspend fun orphanedAttachmentPaths(groupId: String): List<String>

    /**
     * Groups this device holds captures for although their review is off, or that it no longer holds
     * at all: what a member's device kept before 2.0.2, when only the creator's erased.
     */
    @Query(
        "SELECT DISTINCT h.groupId FROM message_edit_history h WHERE NOT EXISTS " +
            "(SELECT 1 FROM chat_group g WHERE g.id = h.groupId AND g.auditRetention = 1)",
    )
    suspend fun groupsHeldWithoutReview(): List<String>

    /** Called when retention is switched off; what was kept under the old policy does not linger. */
    @Query("DELETE FROM message_edit_history WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM message_edit_history WHERE capturedAtUnixMs < :cutoffUnixMs")
    suspend fun purgeOlderThan(cutoffUnixMs: Long)
}
