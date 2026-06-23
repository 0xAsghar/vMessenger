package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.EndpointCacheEntity

@Dao
interface EndpointCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EndpointCacheEntity)

    @Query("SELECT * FROM endpoint_cache WHERE identityHash = :hash LIMIT 1")
    suspend fun get(hash: ByteArray): EndpointCacheEntity?

    @Query("DELETE FROM endpoint_cache WHERE expiresAtUnixMs < :now")
    suspend fun purgeExpired(now: Long)
}
