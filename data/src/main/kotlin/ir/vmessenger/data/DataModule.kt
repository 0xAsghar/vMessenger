package ir.vmessenger.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** The app's `versionName` (empty when it cannot be resolved); recorded in exported backups. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppVersionName

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    @AppVersionName
    fun provideAppVersionName(@ApplicationContext context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
}
