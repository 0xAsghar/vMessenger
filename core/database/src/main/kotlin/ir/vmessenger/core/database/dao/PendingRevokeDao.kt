package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ir.vmessenger.core.database.entity.PendingRevokeEntity

@Dao
interface PendingRevokeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(revoke: PendingRevokeEntity)

    @Update
    suspend fun update(revoke: PendingRevokeEntity)

    @Query("SELECT * FROM pending_revoke WHERE nextAttemptUnixMs <= :now")
    suspend fun due(now: Long): List<PendingRevokeEntity>

    @Query("DELETE FROM pending_revoke WHERE identityHash = :identityHash")
    suspend fun delete(identityHash: ByteArray)

    /**
     * Drops the revoke for whoever's identity hash starts with this 16-byte routing prefix
     * (lowercase hex, as produced by `IdentityHashMatcher.routingKeyHex`): a contact re-added by
     * user hash knows only the prefix, while the revoke was queued under the full hash.
     */
    @Query("DELETE FROM pending_revoke WHERE lower(substr(hex(identityHash), 1, 32)) = :routingKeyHex")
    suspend fun deleteByRoutingKey(routingKeyHex: String)

    /** Drops attempts older than [cutoff]: a peer that has not appeared in days is not going to. */
    @Query("DELETE FROM pending_revoke WHERE createdAtUnixMs < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)
}
