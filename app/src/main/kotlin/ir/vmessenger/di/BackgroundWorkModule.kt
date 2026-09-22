package ir.vmessenger.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.app.work.AndroidBackgroundWorkControl
import ir.vmessenger.data.wipe.BackgroundWorkControl
import javax.inject.Singleton

/**
 * Bound here rather than in `:data`, because this is where the work is enqueued and where
 * WorkManager is a dependency at all; `:data` only declares the port the wipe needs.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundWorkModule {
    @Binds
    @Singleton
    abstract fun bindBackgroundWorkControl(impl: AndroidBackgroundWorkControl): BackgroundWorkControl
}
