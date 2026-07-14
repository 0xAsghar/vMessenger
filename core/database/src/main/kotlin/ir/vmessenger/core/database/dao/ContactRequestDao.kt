package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ir.vmessenger.core.database.entity.ContactRequestEntity
import ir.vmessenger.core.database.entity.ContactRequestStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactRequestDao {
    @Query("SELECT * FROM contact_request WHERE status = 'PENDING' ORDER BY receivedAtUnixMs DESC")
    fun observePending(): Flow<List<ContactRequestEntity>>

    @Query("SELECT * FROM contact_request WHERE requestId = :requestId LIMIT 1")
    suspend fun getById(requestId: String): ContactRequestEntity?

    @Query("SELECT * FROM contact_request WHERE requesterIdentityHash = :hash LIMIT 1")
    suspend fun getByRequesterHash(hash: ByteArray): ContactRequestEntity?

    @Query("SELECT rejectCount FROM contact_request WHERE requestId = :requestId LIMIT 1")
    suspend fun rejectCountOf(requestId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContactRequestEntity)

    @Update
    suspend fun update(entity: ContactRequestEntity)

    @Query("DELETE FROM contact_request WHERE requestId = :requestId")
    suspend fun deleteById(requestId: String)
}
