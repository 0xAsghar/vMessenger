package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.DhtRecordEntity

@Dao
interface DhtRecordDao {
    @Query("SELECT * FROM dht_record WHERE expiresAtUnixMs > :nowMs ORDER BY sequence DESC")
    suspend fun active(nowMs: Long): List<DhtRecordEntity>

    @Query("SELECT COUNT(*) FROM dht_record")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DhtRecordEntity)

    @Query("DELETE FROM dht_record WHERE expiresAtUnixMs <= :nowMs")
    suspend fun purgeExpired(nowMs: Long)

    @Query(
        """
        DELETE FROM dht_record WHERE recordKey IN (
            SELECT recordKey FROM dht_record ORDER BY storedAtUnixMs ASC LIMIT :excess
        )
        """,
    )
    suspend fun evictOldest(excess: Int)
}
