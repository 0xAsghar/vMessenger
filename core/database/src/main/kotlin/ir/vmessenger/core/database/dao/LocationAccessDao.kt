package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.vmessenger.core.database.entity.LocationAccessEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationAccessDao {
    @Query("SELECT * FROM location_access WHERE canSeeMyLocation = 1")
    fun observeGranted(): Flow<List<LocationAccessEntity>>

    @Query("SELECT * FROM location_access")
    fun observeAll(): Flow<List<LocationAccessEntity>>

    @Query("SELECT * FROM location_access WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: String): LocationAccessEntity?

    @Query("SELECT contactId FROM location_access WHERE canSeeMyLocation = 1")
    suspend fun grantedContactIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocationAccessEntity)

    @Query("DELETE FROM location_access WHERE contactId = :contactId")
    suspend fun deleteByContactId(contactId: String)
}
