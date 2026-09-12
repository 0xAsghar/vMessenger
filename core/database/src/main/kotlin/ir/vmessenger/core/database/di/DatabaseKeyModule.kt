package ir.vmessenger.core.database.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.database.DatabasePassphraseSource
import ir.vmessenger.core.database.KeystoreDatabasePassphraseSource
import javax.inject.Singleton

/** Binds the production (Android Keystore) source of the SQLCipher passphrase. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseKeyModule {
    @Binds
    @Singleton
    abstract fun bindDatabasePassphraseSource(impl: KeystoreDatabasePassphraseSource): DatabasePassphraseSource
}
