package ir.vmessenger.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.database.VMessengerDatabase
import ir.vmessenger.core.database.dao.AppMetadataDao
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DATABASE_NAME = "vmessenger.db"

    @Provides
    @Singleton
    fun provideDatabasePassphrase(): ByteArray {
        // Phase 2 replaces this with a Keystore-wrapped key.
        return SQLiteDatabase.getBytes("vmessenger-phase1-placeholder".toCharArray())
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphrase: ByteArray,
    ): VMessengerDatabase {
        val factory = SupportFactory(passphrase)
        return Room.databaseBuilder(
            context,
            VMessengerDatabase::class.java,
            DATABASE_NAME,
        )
            .openHelperFactory(factory)
            .build()
    }

    @Provides
    fun provideAppMetadataDao(database: VMessengerDatabase): AppMetadataDao = database.appMetadataDao()
}
