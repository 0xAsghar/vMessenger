package ir.vmessenger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.MainActivity
import ir.vmessenger.core.notifications.NotificationTarget
import javax.inject.Singleton

/**
 * Tells `core:notifications` which activity a notification tap should open.
 * The dependency points this way on purpose: the notification module stays free
 * of any knowledge of the app's UI.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationTargetModule {
    @Provides
    @Singleton
    fun provideNotificationTarget(): NotificationTarget = MainActivityTarget

    private object MainActivityTarget : NotificationTarget {
        override val activityClass: Class<*> = MainActivity::class.java
    }
}
