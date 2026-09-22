package ir.vmessenger.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.CallActivity
import ir.vmessenger.MainActivity
import ir.vmessenger.app.call.CallActionReceiver
import ir.vmessenger.core.notifications.CallNotificationTarget
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

    /** A call opens its own activity, not the main one; see [CallActivity]. */
    @Provides
    @Singleton
    fun provideCallNotificationTarget(): CallNotificationTarget = CallTarget

    private object MainActivityTarget : NotificationTarget {
        override val activityClass: Class<*> = MainActivity::class.java
    }

    private object CallTarget : CallNotificationTarget {
        override val callActivityClass: Class<*> = CallActivity::class.java
        override val callActionReceiverClass: Class<*> = CallActionReceiver::class.java
    }
}
