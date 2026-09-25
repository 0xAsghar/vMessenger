package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import ir.vmessenger.core.database.entity.ManagedNodeEntity
import kotlinx.coroutines.flow.Flow

/** The servers this device set up. No row has children, so an upsert cascades nothing away. */
@Dao
interface ManagedNodeDao {
    @Query("SELECT * FROM managed_node ORDER BY createdAtUnixMs DESC")
    fun observeAll(): Flow<List<ManagedNodeEntity>>

    @Query("SELECT * FROM managed_node WHERE id = :id")
    suspend fun getById(id: String): ManagedNodeEntity?

    @Query("SELECT * FROM managed_node WHERE host = :host AND sshPort = :sshPort")
    suspend fun getByHost(host: String, sshPort: Int): ManagedNodeEntity?

    @Upsert
    suspend fun upsert(entity: ManagedNodeEntity)

    @Query("UPDATE managed_node SET lastCheckedUnixMs = :atUnixMs, lastCheckOk = :ok WHERE id = :id")
    suspend fun markChecked(id: String, atUnixMs: Long, ok: Boolean)

    @Query("DELETE FROM managed_node WHERE id = :id")
    suspend fun delete(id: String)
}
