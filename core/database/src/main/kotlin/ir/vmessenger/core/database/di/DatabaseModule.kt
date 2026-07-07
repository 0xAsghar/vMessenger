package ir.vmessenger.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.core.database.VMessengerDatabase
import ir.vmessenger.core.database.dao.AppMetadataDao
import ir.vmessenger.core.database.dao.BootstrapNodeDao
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ContactRequestDao
import ir.vmessenger.core.database.dao.LocationAccessDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.DhtRecordDao
import ir.vmessenger.core.database.dao.EndpointCacheDao
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.dao.KeyMaterialDao
import ir.vmessenger.core.database.dao.LocationSampleDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.dao.MailboxDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.dao.RelayNodeDao
import ir.vmessenger.core.database.dao.SessionDao
import ir.vmessenger.core.database.migration.MIGRATION_1_2
import ir.vmessenger.core.database.migration.MIGRATION_2_3
import ir.vmessenger.core.database.migration.MIGRATION_3_4
import ir.vmessenger.core.database.migration.MIGRATION_4_5
import ir.vmessenger.core.database.migration.MIGRATION_5_6
import ir.vmessenger.core.database.migration.MIGRATION_6_7
import ir.vmessenger.core.database.migration.MIGRATION_7_8
import ir.vmessenger.core.database.migration.MIGRATION_8_9
import ir.vmessenger.core.database.migration.MIGRATION_10_11
import ir.vmessenger.core.database.migration.MIGRATION_11_12
import ir.vmessenger.core.database.migration.MIGRATION_9_10
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions")
object DatabaseModule {
    private const val DATABASE_NAME = "vmessenger.db"

    @Provides
    @Singleton
    fun provideDatabasePassphrase(databaseKeyProvider: DatabaseKeyProvider): ByteArray =
        databaseKeyProvider.getPassphrase()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphrase: ByteArray,
    ): VMessengerDatabase {
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(
            context,
            VMessengerDatabase::class.java,
            DATABASE_NAME,
        )
            .openHelperFactory(factory)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
            )
            .build()
    }

    @Provides
    fun provideAppMetadataDao(database: VMessengerDatabase): AppMetadataDao = database.appMetadataDao()

    @Provides
    fun provideIdentityDao(database: VMessengerDatabase): IdentityDao = database.identityDao()

    @Provides
    fun provideKeyMaterialDao(database: VMessengerDatabase): KeyMaterialDao = database.keyMaterialDao()

    @Provides
    fun provideContactDao(database: VMessengerDatabase): ContactDao = database.contactDao()

    @Provides
    fun provideContactRequestDao(database: VMessengerDatabase): ContactRequestDao = database.contactRequestDao()

    @Provides
    fun provideLocationAccessDao(database: VMessengerDatabase): LocationAccessDao = database.locationAccessDao()

    @Provides
    fun provideEndpointCacheDao(database: VMessengerDatabase): EndpointCacheDao = database.endpointCacheDao()

    @Provides
    fun provideBootstrapNodeDao(database: VMessengerDatabase): BootstrapNodeDao = database.bootstrapNodeDao()

    @Provides
    fun provideRelayNodeDao(database: VMessengerDatabase): RelayNodeDao = database.relayNodeDao()

    @Provides
    fun provideConversationDao(database: VMessengerDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: VMessengerDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideOutboxDao(database: VMessengerDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideSessionDao(database: VMessengerDatabase): SessionDao = database.sessionDao()

    @Provides
    fun provideLocationShareDao(database: VMessengerDatabase): LocationShareDao = database.locationShareDao()

    @Provides
    fun provideLocationSampleDao(database: VMessengerDatabase): LocationSampleDao = database.locationSampleDao()

    @Provides
    fun provideMailboxDao(database: VMessengerDatabase): MailboxDao = database.mailboxDao()

    @Provides
    fun provideDhtRecordDao(database: VMessengerDatabase): DhtRecordDao = database.dhtRecordDao()
}
