package ir.vmessenger.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import ir.vmessenger.core.database.dao.AppMetadataDao
import ir.vmessenger.core.database.entity.AppMetadataEntity

@Database(
    entities = [AppMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VMessengerDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao
}
