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
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.nio.charset.StandardCharsets
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DATABASE_NAME = "vmessenger.db"

    @Provides
    @Singleton
    fun provideDatabasePassphrase(): ByteArray {
        // Phase 2 replaces this with a Keystore-wrapped key.
        return "vmessenger-phase1-placeholder".toByteArray(StandardCharsets.UTF_8)
    }

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
            .build()
    }

    @Provides
    fun provideAppMetadataDao(database: VMessengerDatabase): AppMetadataDao = database.appMetadataDao()
}
