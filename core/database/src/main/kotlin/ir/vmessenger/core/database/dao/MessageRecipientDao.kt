package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageRecipientEntity

@Dao
interface MessageRecipientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageRecipientEntity>)

    @Query("SELECT * FROM message_recipient WHERE messageId = :messageId")
    suspend fun forMessage(messageId: String): List<MessageRecipientEntity>

    /**
     * Only ever moves a recipient forward (QUEUED → SENT → DELIVERED → READ), so a
     * re-sent message or a receipt that overtakes another cannot regress the state
     * the aggregate is computed from. [rank] is the caller's order for [status].
     */
    @Query(
        """
        UPDATE message_recipient SET
            status = :status,
            sentAtUnixMs = CASE WHEN :sentAt IS NOT NULL THEN :sentAt ELSE sentAtUnixMs END,
            deliveredAtUnixMs = CASE WHEN :deliveredAt IS NOT NULL THEN :deliveredAt ELSE deliveredAtUnixMs END,
            readAtUnixMs = CASE WHEN :readAt IS NOT NULL THEN :readAt ELSE readAtUnixMs END
        WHERE messageId = :messageId AND identityHash = :identityHash
          AND (CASE status
                WHEN 'QUEUED' THEN 0 WHEN 'FAILED' THEN 1 WHEN 'SENT' THEN 2
                WHEN 'DELIVERED' THEN 3 ELSE 4 END) < :rank
        """,
    )
    @Suppress("LongParameterList") // one parameter per timestamp column the status may carry
    suspend fun advance(
        messageId: String,
        identityHash: String,
        status: DeliveryStatus,
        rank: Int,
        sentAt: Long?,
        deliveredAt: Long?,
        readAt: Long?,
    )

    /** Terminal failure of one recipient; unlike [advance] this may move backwards from SENT. */
    @Query(
        "UPDATE message_recipient SET status = 'FAILED' WHERE messageId = :messageId AND identityHash = :identityHash",
    )
    suspend fun markFailed(messageId: String, identityHash: String)

    @Query("DELETE FROM message_recipient WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)
}
