package ir.vmessenger.app.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.notifications.CallNotificationManager
import javax.inject.Inject

/**
 * Declares, for as long as a call is up, that this app is using the microphone.
 *
 * It holds no audio itself — the media path does that — and exists for two reasons. The platform
 * requires it: without a `microphone` foreground service the capture stops the moment the app is
 * backgrounded. And the user is owed it: a service of this type puts a notification in the shade
 * and lights the privacy indicator, so "this app is listening" is visible the whole time it is true.
 *
 * It is never started while a call is merely ringing. See
 * [ir.vmessenger.data.call.CallState.holdsMicrophoneService] — that is both Android 14's rule and
 * the property worth having.
 */
@AndroidEntryPoint
class CallForegroundService : Service() {
    @Inject
    lateinit var callNotificationManager: CallNotificationManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(CallNotificationManager.EXTRA_CALL_ID)
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME)
        if (callId == null || peerName == null) {
            AppLogger.warn(TAG, "started without a call; standing down")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val connecting = intent.getBooleanExtra(EXTRA_CONNECTING, true)
        enterForeground(callId, peerName, connecting)
        // Not sticky: a call cannot be resumed by restarting a service, and a system restart with
        // no call in progress would put a microphone notification in the shade over nothing.
        return START_NOT_STICKY
    }

    private fun enterForeground(callId: String, peerName: String, connecting: Boolean) {
        val notification = callNotificationManager.buildOngoing(callId, peerName, connecting)
        val id = CallNotificationManager.NOTIFICATION_ID_CALL_ONGOING
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(id, notification)
            }
        }.onFailure {
            // A refused microphone service means the platform will not let this call capture audio.
            // Standing down is honest; carrying on would leave a call that cannot be heard.
            AppLogger.warn(TAG, "microphone foreground service refused: ${it.message}")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Call"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_CONNECTING = "connecting"

        fun startIntent(context: Context, callId: String, peerName: String, connecting: Boolean): Intent =
            Intent(context, CallForegroundService::class.java).apply {
                putExtra(CallNotificationManager.EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_CONNECTING, connecting)
            }
    }
}
