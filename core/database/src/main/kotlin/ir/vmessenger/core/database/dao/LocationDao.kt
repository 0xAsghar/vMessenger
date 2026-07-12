package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MessageDirection
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationShareDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocationShareEntity)

    @Query("SELECT * FROM location_share WHERE active = 1")
    fun observeActive(): Flow<List<LocationShareEntity>>

    @Query("SELECT * FROM location_share WHERE shareId = :shareId LIMIT 1")
    suspend fun getById(shareId: String): LocationShareEntity?

    @Query("SELECT * FROM location_share WHERE contactId = :contactId AND active = 1 LIMIT 1")
    suspend fun getActiveByContact(contactId: String): LocationShareEntity?

    @Query("SELECT * FROM location_share WHERE contactId = :contactId AND direction = :direction AND active = 1 LIMIT 1")
    suspend fun getActiveByContactAndDirection(contactId: String, direction: MessageDirection): LocationShareEntity?

    @Update
    suspend fun update(entity: LocationShareEntity)
}

@Dao
interface LocationSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sample: LocationSampleEntity)

    @Query("SELECT * FROM location_sample WHERE shareId = :shareId ORDER BY sampledAtUnixMs DESC LIMIT 1")
    fun observeLatest(shareId: String): Flow<LocationSampleEntity?>

    @Query("SELECT * FROM location_sample WHERE shareId = :shareId ORDER BY sampledAtUnixMs DESC LIMIT 1")
    suspend fun getLatest(shareId: String): LocationSampleEntity?

    /**
     * Latest sample per share, reactive on the sample table so the map refreshes
     * whenever a new position is recorded (not only when shares start/stop).
     */
    @Query("SELECT * FROM location_sample WHERE id IN (SELECT MAX(id) FROM location_sample GROUP BY shareId)")
    fun observeLatestPerShare(): Flow<List<LocationSampleEntity>>
}
