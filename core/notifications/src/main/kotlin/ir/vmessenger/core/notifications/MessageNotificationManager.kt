package ir.vmessenger.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            "پیام‌ها",
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
    }

    @Suppress("TooGenericExceptionCaught")
    fun showMessageNotification(
        senderName: String,
        preview: String,
        conversationId: String,
        hideContent: Boolean,
    ) {
        val title = if (hideContent) "vMessenger" else senderName
        val text = if (hideContent) "پیام جدید" else preview
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(launchAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            // One notification per conversation; a newer message replaces the old.
            manager.notify(conversationId.hashCode(), notification)
        } catch (e: Exception) {
            // Notification permission may be revoked; delivery must not fail.
            android.util.Log.w("Notifications", "notify failed: ${e.message}")
        }
    }

    private fun launchAppIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
    }
}
