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

    @Query("SELECT * FROM bootstrap_node WHERE enabled = 1")
    suspend fun getEnabled(): List<BootstrapNodeEntity>
}
