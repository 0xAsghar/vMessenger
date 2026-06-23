package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    @Query("SELECT * FROM conversation ORDER BY lastActivityUnixMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: String): ConversationEntity?
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM message WHERE conversationId = :cid ORDER BY createdAtUnixMs ASC")
    fun observeConversation(cid: String): Flow<List<MessageEntity>>

    @Query("UPDATE message SET status = :status, deliveredAtUnixMs = :ts WHERE messageId = :id")
    suspend fun markDelivered(id: String, status: DeliveryStatus, ts: Long)

    @Query("UPDATE message SET status = :status, readAtUnixMs = :ts WHERE messageId = :id")
    suspend fun markRead(id: String, status: DeliveryStatus, ts: Long)

    @Query("UPDATE message SET status = :status, sentAtUnixMs = :ts WHERE messageId = :id")
    suspend fun markSent(id: String, status: DeliveryStatus, ts: Long)

    @Query("UPDATE message SET status = :status WHERE messageId = :id")
    suspend fun updateStatus(id: String, status: DeliveryStatus)

    @Query("SELECT * FROM message WHERE messageId = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE nextAttemptUnixMs <= :now ORDER BY nextAttemptUnixMs ASC")
    suspend fun due(now: Long): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    suspend fun remove(messageId: String)

    @Update
    suspend fun update(item: OutboxEntity)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM session WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: String): SessionEntity?

    @Query("DELETE FROM session WHERE contactId = :contactId")
    suspend fun delete(contactId: String)
}
