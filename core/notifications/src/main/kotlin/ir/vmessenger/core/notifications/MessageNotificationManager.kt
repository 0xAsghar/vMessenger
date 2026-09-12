package ir.vmessenger.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raises (and dismisses) the "new message" notification.
 *
 * Lock-screen privacy: the channel and every notification are
 * `VISIBILITY_PRIVATE`, so a locked device shows only the *public version* —
 * a content-free "پیام جدید" with no sender name and no preview. With the
 * "hide notification content" preference on, the public version is
 * `VISIBILITY_SECRET` as well, which keeps it off the lock screen entirely.
 */
@Singleton
class MessageNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("TooGenericExceptionCaught")
    fun showMessageNotification(
        senderName: String,
        preview: String,
        conversationId: String,
        hideContent: Boolean,
    ) {
        val generic = context.getString(R.string.notification_new_message)
        val title = if (hideContent) APP_TITLE else senderName
        val text = if (hideContent) generic else preview
        val notification = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(hideContent, generic))
            .build()
        try {
            // One notification per conversation; a newer message replaces the old.
            manager.notify(conversationId.hashCode(), notification)
        } catch (e: Exception) {
            // Notification permission may be revoked; delivery must not fail.
            android.util.Log.w("Notifications", "notify failed: ${e.message}")
        }
    }

    /** Dismisses the notification of [conversationId] (the chat was opened or marked read). */
    fun cancel(conversationId: String) {
        runCatching { manager.cancel(conversationId.hashCode()) }
    }

    /** Dismisses every notification this app raised (secure wipe). */
    fun cancelAll() {
        runCatching { manager.cancelAll() }
    }

    /**
     * What a locked device is allowed to render: no sender, no preview, no
     * conversation id. SECRET when the user asked to hide notification content,
     * so nothing at all reaches the lock screen.
     */
    private fun publicVersion(hideContent: Boolean, generic: String): Notification =
        baseBuilder()
            .setContentTitle(generic)
            .setVisibility(
                if (hideContent) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC,
            )
            .build()

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(launchAppIntent())

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

        /** Brand name, deliberately not a translatable resource. */
        private const val APP_TITLE = "vMessenger"
    }
}
