package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.MailboxBlobEntity

@Dao
interface MailboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blob: MailboxBlobEntity)

    @Query("SELECT * FROM mailbox_blob WHERE recipientIdentityHash = :hash AND expiresAtUnixMs > :now")
    suspend fun forRecipient(hash: ByteArray, now: Long): List<MailboxBlobEntity>

    @Query("SELECT * FROM mailbox_blob WHERE blobId = :blobId LIMIT 1")
    suspend fun getById(blobId: String): MailboxBlobEntity?

    @Query("SELECT COUNT(*) FROM mailbox_blob WHERE expiresAtUnixMs > :now")
    suspend fun countActive(now: Long): Int

    @Query("DELETE FROM mailbox_blob WHERE blobId = :blobId")
    suspend fun delete(blobId: String)

    @Query("DELETE FROM mailbox_blob WHERE expiresAtUnixMs <= :now")
    suspend fun purgeExpired(now: Long)
}
