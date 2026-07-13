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
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
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
        return START_STICKY
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
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        manager.removeUpdates(this)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        createChannel()
        val stopIntent = Intent(this, LocationService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(getString(R.string.location_notification_body))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.location_notification_stop),
                stopPending,
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.location_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "ir.vmessenger.location.STOP"
        private const val CHANNEL_ID = "location_sharing"
        private const val NOTIFICATION_ID = 2001
        private const val INTERVAL_MS = 15_000L

        // 0 so a stationary device still receives periodic updates; with a
        // distance filter some devices never deliver the first callback at all.
        private const val MIN_DISTANCE_M = 0f

        fun start(context: Context) {
            val intent = Intent(context, LocationService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
