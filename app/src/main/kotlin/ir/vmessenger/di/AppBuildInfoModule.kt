package ir.vmessenger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.BuildConfig
import ir.vmessenger.core.common.AppBuildInfo
import javax.inject.Singleton

/** Binds [AppBuildInfo] to the generated `BuildConfig` of the app module. */
@Module
@InstallIn(SingletonComponent::class)
object AppBuildInfoModule {
    @Provides
    @Singleton
    fun provideAppBuildInfo(): AppBuildInfo = BuildConfigAppBuildInfo

    private object BuildConfigAppBuildInfo : AppBuildInfo {
        override val isDebug: Boolean = BuildConfig.DEBUG
        override val versionName: String = BuildConfig.VERSION_NAME
        override val versionCode: Long = BuildConfig.VERSION_CODE.toLong()
    }
}
