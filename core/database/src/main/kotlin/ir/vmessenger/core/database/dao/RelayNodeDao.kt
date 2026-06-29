package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.RelayNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RelayNodeEntity)

    @Query("SELECT * FROM relay_node ORDER BY priority DESC, failCount ASC")
    fun observeAll(): Flow<List<RelayNodeEntity>>

    /**
     * Enabled relays ordered healthiest-first: fewest recent failures, then most
     * recent success, then highest priority.
     */
    @Query(
        "SELECT * FROM relay_node WHERE enabled = 1 " +
            "ORDER BY failCount ASC, lastOkUnixMs DESC, priority DESC",
    )
    suspend fun getEnabledOrdered(): List<RelayNodeEntity>

    @Query("SELECT * FROM relay_node")
    suspend fun getAll(): List<RelayNodeEntity>

    @Query("SELECT * FROM relay_node WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): RelayNodeEntity?

    @Query("UPDATE relay_node SET lastOkUnixMs = :ts, failCount = 0 WHERE address = :address")
    suspend fun markOk(address: String, ts: Long)

    @Query("UPDATE relay_node SET lastFailUnixMs = :ts, failCount = failCount + 1 WHERE address = :address")
    suspend fun markFail(address: String, ts: Long)

    @Query("UPDATE relay_node SET enabled = :enabled WHERE address = :address")
    suspend fun setEnabled(address: String, enabled: Boolean)

    @Query("DELETE FROM relay_node WHERE address = :address")
    suspend fun deleteByAddress(address: String)
}
