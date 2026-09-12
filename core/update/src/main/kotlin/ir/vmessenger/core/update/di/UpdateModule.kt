package ir.vmessenger.core.update.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.update.AndroidUpdateInstaller
import ir.vmessenger.core.update.UpdateInstaller
import ir.vmessenger.core.update.UpdatePreferences
import ir.vmessenger.core.update.UpdateStore
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** `cacheDir/updates`, the one directory the app's FileProvider exposes to the installer. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdatesDir

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateBindingsModule {
    @Binds
    @Singleton
    abstract fun bindUpdateStore(impl: UpdatePreferences): UpdateStore

    @Binds
    @Singleton
    abstract fun bindUpdateInstaller(impl: AndroidUpdateInstaller): UpdateInstaller
}

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    /** The name has to stay in step with `<cache-path name="updates" path="updates/" />`. */
    private const val UPDATES_DIRECTORY = "updates"

    @Provides
    @Singleton
    @UpdatesDir
    fun provideUpdatesDirectory(@ApplicationContext context: Context): File =
        File(context.cacheDir, UPDATES_DIRECTORY)
}
