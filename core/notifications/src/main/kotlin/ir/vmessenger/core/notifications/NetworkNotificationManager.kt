package ir.vmessenger.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        // Resolved through the app's language, not the device's; see [localised].
        val strings = context.localised()
        val channel = NotificationChannel(
            CHANNEL_NETWORK,
            strings.getString(R.string.notification_channel_network),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = strings.getString(R.string.notification_channel_network_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_NETWORK)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(APP_NAME)
            .setContentText(context.localised().getString(R.string.notification_network_connected))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    companion object {
        /** Never translated. */
        private const val APP_NAME = "vMessenger"

        const val CHANNEL_NETWORK = "network"

        /** Unique among foreground notifications: LocationService's is 2002, and sharing an id merges them. */
        const val NOTIFICATION_ID_NETWORK = 2001
    }
}
