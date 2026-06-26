package ir.vmessenger.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ir.vmessenger.core.database.converter.EnumConverters
import ir.vmessenger.core.database.dao.AppMetadataDao
import ir.vmessenger.core.database.dao.BootstrapNodeDao
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.EndpointCacheDao
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.dao.KeyMaterialDao
import ir.vmessenger.core.database.dao.LocationSampleDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.dao.SessionDao
import ir.vmessenger.core.database.entity.AppMetadataEntity
import ir.vmessenger.core.database.entity.BootstrapNodeEntity
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.EndpointCacheEntity
import ir.vmessenger.core.database.entity.IdentityEntity
import ir.vmessenger.core.database.entity.KeyMaterialEntity
import ir.vmessenger.core.database.entity.LocationSampleEntity
import ir.vmessenger.core.database.entity.LocationShareEntity
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.database.entity.SessionEntity

@Database(
    entities = [
        AppMetadataEntity::class,
        IdentityEntity::class,
        KeyMaterialEntity::class,
        ContactEntity::class,
        EndpointCacheEntity::class,
        BootstrapNodeEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        SessionEntity::class,
        LocationShareEntity::class,
        LocationSampleEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(EnumConverters::class)
@Suppress("TooManyFunctions")
abstract class VMessengerDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun identityDao(): IdentityDao
    abstract fun keyMaterialDao(): KeyMaterialDao
    abstract fun contactDao(): ContactDao
    abstract fun endpointCacheDao(): EndpointCacheDao
    abstract fun bootstrapNodeDao(): BootstrapNodeDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun sessionDao(): SessionDao
    abstract fun locationShareDao(): LocationShareDao
    abstract fun locationSampleDao(): LocationSampleDao
}
