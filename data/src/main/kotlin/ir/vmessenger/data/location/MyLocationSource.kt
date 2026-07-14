package ir.vmessenger.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** This device's own latitude/longitude, used to show distance to contacts sharing location. */
data class LatLng(val latitude: Double, val longitude: Double)

@Singleton
class MyLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Emits the last-known fix immediately, then live updates while observed —
     * independent of whether this device is sharing its own location, so a
     * viewer can see the distance to a contact. Null when no fix/permission is
     * available; callers should then omit distance rather than fail.
     */
    @SuppressLint("MissingPermission")
    fun observe(): Flow<LatLng?> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        trySend(lastKnown(manager))
        val listener = LocationListener { location ->
            trySend(LatLng(location.latitude, location.longitude))
        }
        val registered = runCatching {
            var any = false
            for (provider in PROVIDERS) {
                if (manager?.isProviderEnabled(provider) == true) {
                    manager.requestLocationUpdates(provider, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
                    any = true
                }
            }
            any
        }.getOrDefault(false)
        awaitClose {
            if (registered) runCatching { manager?.removeUpdates(listener) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager?): LatLng? = runCatching {
        manager ?: return null
        val fix = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        fix?.let { LatLng(it.latitude, it.longitude) }
    }.getOrNull()

    companion object {
        private const val INTERVAL_MS = 10_000L
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
