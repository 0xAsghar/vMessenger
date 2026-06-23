package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationShareDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocationShareEntity)

    @Query("SELECT * FROM location_share WHERE active = 1")
    fun observeActive(): Flow<List<LocationShareEntity>>

    @Query("SELECT * FROM location_share WHERE shareId = :shareId LIMIT 1")
    suspend fun getById(shareId: String): LocationShareEntity?

    @Update
    suspend fun update(entity: LocationShareEntity)
}

@Dao
interface LocationSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: LocationSampleEntity)

    @Query("SELECT * FROM location_sample WHERE shareId = :shareId ORDER BY sampledAtUnixMs DESC LIMIT 1")
    fun observeLatest(shareId: String): Flow<LocationSampleEntity?>
}
