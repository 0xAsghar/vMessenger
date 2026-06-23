package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.AppMetadataEntity

@Dao
interface AppMetadataDao {
    @Query("SELECT * FROM app_metadata WHERE id = 0 LIMIT 1")
    suspend fun get(): AppMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppMetadataEntity)
}
