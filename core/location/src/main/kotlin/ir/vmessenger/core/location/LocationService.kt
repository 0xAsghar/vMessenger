package ir.vmessenger.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class LocationService : Service(), LocationListener {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // Tells DeviceLocationProvider to stand down: while this service runs it is the only
        // location listener in the process.
        LocationUpdateBus.setServiceRunning(true)
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            // Emit the last known fix right away so sharing starts with a position
            // instead of waiting for the first provider callback (a cold GPS can
            // take minutes; a stationary device may never trigger one).
            val lastKnown = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let { onLocationChanged(it) }
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    INTERVAL_MS,
                    MIN_DISTANCE_M,
                    this,
                    Looper.getMainLooper(),
                )
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    INTERVAL_MS,
                    MIN_DISTANCE_M,
                    this,
                    Looper.getMainLooper(),
                )
            }
        } catch (_: SecurityException) {
            // Permission not granted; service stays idle
        }
        // NOT sticky. A redelivered null intent used to fall straight through to the code above
        // and re-register GPS after a process kill, even when the user had turned sharing off.
        // Restoring a genuinely active share is LocationSharingCoordinator's job, deliberately.
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        LocationUpdateBus.emit(
            LocationUpdate(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = location.accuracy,
                sampledAtUnixMs = System.currentTimeMillis(),
            ),
        )
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LocationUpdateBus.setServiceRunning(false)
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        manager.removeUpdates(this)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        createChannel()
        // Broadcast rather than a direct service stop: tapping this must end the share the same
        // way the in-app switch does — marking the rows inactive and telling the peers. Stopping
        // only the service left peers believing the share was still open, and the next start
        // restored it. The app module owns the receiver; this module cannot reach the coordinator.
        val stopIntent = Intent(ACTION_STOP_SHARING).setPackage(packageName)
        val stopPending = PendingIntent.getBroadcast(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localised().getString(R.string.location_notification_title))
            .setContentText(localised().getString(R.string.location_notification_body))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                localised().getString(R.string.location_notification_stop),
                stopPending,
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            localised().getString(R.string.location_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Handled in :app, which can reach the sharing coordinator this module must not depend on. */
        const val ACTION_STOP_SHARING = "ir.vmessenger.location.STOP_SHARING"
        private const val CHANNEL_ID = "location_sharing"

        /**
         * Not 2001: that is `NetworkNotificationManager.NOTIFICATION_ID_NETWORK`, the always-on
         * network service's notification. Sharing one id, starting this service overwrote that
         * notification with this one, and stopping it left the network service showing «اشتراک
         * موقعیت فعال است» — GPS off, share over, notification still claiming otherwise.
         */
        private const val NOTIFICATION_ID = 2002
        private const val INTERVAL_MS = 15_000L

        // 0 so a stationary device still receives periodic updates; with a
        // distance filter some devices never deliver the first callback at all.
        private const val MIN_DISTANCE_M = 0f

        fun start(context: Context) {
            val intent = Intent(context, LocationService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * stopService, not a startService carrying a stop action: on Android 12+ the latter throws
         * BackgroundServiceStartNotAllowedException from a backgrounded process, and the call site
         * swallowed it — so blocking a contact from a background event left GPS running.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
