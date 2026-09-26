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

    /** Most recent session in one direction, ended or still running, for replaying what was shared. */
    @Query(
        "SELECT * FROM location_share WHERE contactId = :contactId AND direction = :direction " +
            "ORDER BY startedAtUnixMs DESC LIMIT 1",
    )
    suspend fun latestByContactAndDirection(contactId: String, direction: MessageDirection): LocationShareEntity?

    @Update
    suspend fun update(entity: LocationShareEntity)

    /** Removes every share (both directions) with [contactId]; samples cascade. */
    @Query("DELETE FROM location_share WHERE contactId = :contactId")
    suspend fun deleteByContact(contactId: String)

    /** Every share id, active or not (retention walks them all). */
    @Query("SELECT shareId FROM location_share")
    suspend fun allShareIds(): List<String>

    /** Removes shares that ended before [ts]; their samples cascade. */
    @Query("DELETE FROM location_share WHERE active = 0 AND endedAtUnixMs IS NOT NULL AND endedAtUnixMs < :ts")
    suspend fun deleteEndedBefore(ts: Long)
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
     * A contact's positions in one [direction], across all their sharing sessions still inside
     * retention, newest first. Reactive on both tables, so a new sample or an ended share updates it.
     */
    @Query(
        "SELECT location_sample.* FROM location_sample " +
            "INNER JOIN location_share ON location_sample.shareId = location_share.shareId " +
            "WHERE location_share.contactId = :contactId AND location_share.direction = :direction " +
            "ORDER BY location_sample.sampledAtUnixMs DESC LIMIT :limit",
    )
    fun observeForContact(contactId: String, direction: MessageDirection, limit: Int): Flow<List<LocationSampleEntity>>

    /** Every sample of one session, oldest first: the route as it was shared. */
    @Query("SELECT * FROM location_sample WHERE shareId = :shareId ORDER BY sampledAtUnixMs ASC")
    suspend fun samplesForShare(shareId: String): List<LocationSampleEntity>

    /**
     * Latest sample per share, reactive on the sample table so the map refreshes
     * whenever a new position is recorded (not only when shares start/stop).
     */
    @Query(
        "SELECT * FROM location_sample WHERE id IN (SELECT MAX(id) FROM location_sample GROUP BY shareId)",
    )
    fun observeLatestPerShare(): Flow<List<LocationSampleEntity>>

    /** Drops every sample older than [cutoff] (retention window). */
    @Query("DELETE FROM location_sample WHERE sampledAtUnixMs < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)

    /** Keeps only the newest [keep] samples of [shareId]. */
    @Query(
        "DELETE FROM location_sample WHERE shareId = :shareId AND id NOT IN (" +
            "SELECT id FROM location_sample WHERE shareId = :shareId " +
            "ORDER BY sampledAtUnixMs DESC, id DESC LIMIT :keep)",
    )
    suspend fun deleteExcessForShare(shareId: String, keep: Int)
}
