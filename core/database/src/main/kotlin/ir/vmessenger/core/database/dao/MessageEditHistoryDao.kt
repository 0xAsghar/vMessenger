package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ir.vmessenger.core.database.entity.MessageEditHistoryEntity

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

    @Query("SELECT * FROM message_edit_history WHERE messageId = :messageId ORDER BY capturedAtUnixMs ASC")
    suspend fun forMessage(messageId: String): List<MessageEditHistoryEntity>

    @Query("SELECT COUNT(*) FROM message_edit_history WHERE groupId = :groupId")
    suspend fun countForGroup(groupId: String): Int

    /** Called when retention is switched off; what was kept under the old policy does not linger. */
    @Query("DELETE FROM message_edit_history WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM message_edit_history WHERE capturedAtUnixMs < :cutoffUnixMs")
    suspend fun purgeOlderThan(cutoffUnixMs: Long)
}
