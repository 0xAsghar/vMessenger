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
        val channel = NotificationChannel(
            CHANNEL_NETWORK,
            "شبکه",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "اتصال دائمی به شبکه غیرمتمرکز"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_NETWORK)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("vMessenger")
            .setContentText("متصل به شبکه")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    companion object {
        const val CHANNEL_NETWORK = "network"
        const val NOTIFICATION_ID_NETWORK = 2001
    }
}
