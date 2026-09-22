package ir.vmessenger.core.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ringing notification and the one that sits there for the duration of a call.
 *
 * Two channels, because they are two different things to the user: an incoming call has to
 * interrupt, and a call in progress must not make a sound every time it is updated. Splitting them
 * also means the user can silence one in system settings without silencing the other.
 *
 * Neither channel bypasses Do Not Disturb. A messenger that rings through a user's silence because
 * its own author decided calls are important is a messenger that gets uninstalled.
 */
@Singleton
class CallNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val target: CallNotificationTarget,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(incomingChannel())
        manager.createNotificationChannel(ongoingChannel())
    }

    /**
     * Whether a ringing call can take over the screen.
     *
     * `USE_FULL_SCREEN_INTENT` is granted on install but revocable by the user from Android 14, and
     * a full-screen intent that is not permitted degrades silently to a heads-up notification. The
     * caller checks this so it can say something true about what will happen.
     */
    fun canRingFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.canUseFullScreenIntent()
        } else {
            true
        }

    /** The ringing notification, with the answer and decline buttons the platform draws for calls. */
    fun showIncoming(callId: String, peerName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_CALL_INCOMING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(peerName)
            .setContentText(TEXT_INCOMING)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(peerName),
                    action(callId, ACTION_DECLINE),
                    action(callId, ACTION_ACCEPT),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            // Dismissing the shade must not silently drop the call: the screen or the buttons
            // answer it, and hanging up is an explicit act.
            .setAutoCancel(false)
            .setFullScreenIntent(screen(callId), true)
            .setContentIntent(screen(callId))
            .build()
        post(notification)
    }

    /**
     * The notification a call in progress runs under. Returned rather than posted, because it is
     * the foreground service's notification and the service must be the one to present it.
     */
    fun buildOngoing(callId: String, peerName: String, connecting: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_CALL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(peerName)
            .setContentText(if (connecting) TEXT_CONNECTING else TEXT_ONGOING)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(peerName), action(callId, ACTION_HANG_UP)))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(!connecting)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(screen(callId))
            .build()

    fun cancelIncoming() {
        manager.cancel(NOTIFICATION_ID_CALL_INCOMING)
    }

    private fun post(notification: Notification) {
        runCatching { manager.notify(NOTIFICATION_ID_CALL_INCOMING, notification) }
            .onFailure { AppLogger.warn(TAG, "could not post the ringing notification: ${it.message}") }
    }

    private fun person(peerName: String): Person =
        Person.Builder().setName(peerName).setImportant(true).build()

    /** Opens the call screen. A new task, so it can come up over the lock screen on its own. */
    private fun screen(callId: String): PendingIntent {
        val intent = Intent(context, target.callActivityClass).apply {
            action = ACTION_SHOW_CALL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getActivity(context, REQUEST_SCREEN, intent, PENDING_FLAGS)
    }

    private fun action(callId: String, actionName: String): PendingIntent {
        val intent = Intent(context, target.callActionReceiverClass).apply {
            action = actionName
            putExtra(EXTRA_CALL_ID, callId)
        }
        // A distinct request code per action: they differ only by their action string, which
        // PendingIntent equality ignores, so sharing one code would make every button the first.
        val requestCode = when (actionName) {
            ACTION_ACCEPT -> REQUEST_ACCEPT
            ACTION_DECLINE -> REQUEST_DECLINE
            else -> REQUEST_HANG_UP
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, PENDING_FLAGS)
    }

    private fun incomingChannel(): NotificationChannel =
        NotificationChannel(CHANNEL_CALL_INCOMING, NAME_INCOMING, NotificationManager.IMPORTANCE_HIGH).apply {
            description = DESCRIPTION_INCOMING
            setShowBadge(false)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

    private fun ongoingChannel(): NotificationChannel =
        NotificationChannel(CHANNEL_CALL_ONGOING, NAME_ONGOING, NotificationManager.IMPORTANCE_LOW).apply {
            description = DESCRIPTION_ONGOING
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

    companion object {
        const val CHANNEL_CALL_INCOMING = "call_incoming"
        const val CHANNEL_CALL_ONGOING = "call_ongoing"

        /** Unique among foreground notifications: network is 2001 and location 2002. */
        const val NOTIFICATION_ID_CALL_ONGOING = 2003
        const val NOTIFICATION_ID_CALL_INCOMING = 2004

        const val ACTION_ACCEPT = "ir.vmessenger.call.ACCEPT"
        const val ACTION_DECLINE = "ir.vmessenger.call.DECLINE"
        const val ACTION_HANG_UP = "ir.vmessenger.call.HANG_UP"
        const val ACTION_SHOW_CALL = "ir.vmessenger.call.SHOW"
        const val EXTRA_CALL_ID = "call_id"

        private const val TAG = "Call"
        private const val REQUEST_SCREEN = 3001
        private const val REQUEST_ACCEPT = 3002
        private const val REQUEST_DECLINE = 3003
        private const val REQUEST_HANG_UP = 3004
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private const val NAME_INCOMING = "تماس ورودی"
        private const val DESCRIPTION_INCOMING = "زنگ تماس‌های دریافتی"
        private const val NAME_ONGOING = "تماس در جریان"
        private const val DESCRIPTION_ONGOING = "نشان‌دادن تماس فعال"
        private const val TEXT_INCOMING = "تماس صوتی ورودی"
        private const val TEXT_CONNECTING = "در حال اتصال…"
        private const val TEXT_ONGOING = "تماس صوتی"
    }
}
