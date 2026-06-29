package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.BootstrapNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BootstrapNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BootstrapNodeEntity)

    @Query("SELECT * FROM bootstrap_node WHERE enabled = 1")
    fun observeEnabled(): Flow<List<BootstrapNodeEntity>>

    @Query("SELECT * FROM bootstrap_node ORDER BY priority DESC, failCount ASC")
    fun observeAll(): Flow<List<BootstrapNodeEntity>>

    @Query("SELECT * FROM bootstrap_node WHERE enabled = 1")
    suspend fun getEnabled(): List<BootstrapNodeEntity>

    /**
     * Enabled nodes ordered so the healthiest are tried first: fewest recent
     * failures, then most recent success, then highest priority.
     */
    @Query(
        "SELECT * FROM bootstrap_node WHERE enabled = 1 " +
            "ORDER BY failCount ASC, lastOkUnixMs DESC, priority DESC",
    )
    suspend fun getEnabledOrdered(): List<BootstrapNodeEntity>

    @Query("SELECT * FROM bootstrap_node")
    suspend fun getAll(): List<BootstrapNodeEntity>

    @Query("SELECT * FROM bootstrap_node WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): BootstrapNodeEntity?

    @Query("UPDATE bootstrap_node SET lastOkUnixMs = :ts, failCount = 0 WHERE address = :address")
    suspend fun markOk(address: String, ts: Long)

    @Query("UPDATE bootstrap_node SET lastFailUnixMs = :ts, failCount = failCount + 1 WHERE address = :address")
    suspend fun markFail(address: String, ts: Long)

    @Query("UPDATE bootstrap_node SET enabled = :enabled WHERE address = :address")
    suspend fun setEnabled(address: String, enabled: Boolean)

    @Query("DELETE FROM bootstrap_node WHERE address = :address")
    suspend fun deleteByAddress(address: String)
}
