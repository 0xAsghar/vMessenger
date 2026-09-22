package ir.vmessenger.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ir.vmessenger.core.database.entity.ActivityLogEntity
import kotlinx.coroutines.flow.Flow

/** The device's own activity log. Append-only, bounded, and erased outright on a wipe. */
@Dao
interface ActivityLogDao {
    @Insert
    suspend fun insert(entity: ActivityLogEntity)

    @Query("SELECT * FROM activity_log ORDER BY atUnixMs DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<ActivityLogEntity>>

    /** The export reads through this, so what leaves the device is the same window the screen shows. */
    @Query("SELECT * FROM activity_log ORDER BY atUnixMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActivityLogEntity>

    @Query("DELETE FROM activity_log WHERE atUnixMs < :cutoffUnixMs")
    suspend fun purgeOlderThan(cutoffUnixMs: Long)

    @Query("DELETE FROM activity_log")
    suspend fun clear()
}
