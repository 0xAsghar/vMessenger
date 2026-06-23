package ir.vmessenger.core.location

import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class LocationUpdate(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val sampledAtUnixMs: Long,
)

class LocationService : Service(), LocationListener {
    private val _updates = MutableSharedFlow<LocationUpdate>(extraBufferCapacity = 16)
    val updates = _updates.asSharedFlow()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                INTERVAL_MS,
                MIN_DISTANCE_M,
                this,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            // Permission not granted; service stays idle
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        _updates.tryEmit(
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

    companion object {
        private const val INTERVAL_MS = 15_000L
        private const val MIN_DISTANCE_M = 10f
    }
}
