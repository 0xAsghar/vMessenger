package ir.vmessenger.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's own position — one consumer per process.
 *
 * While [LocationService] runs it is the only listener and this class just relays its bus; with
 * the service stopped it registers a [LocationManager] listener of its own, and only while
 * somebody is actually collecting. Screens therefore never add a second (or third) registration
 * of their own, which is what previously kept GPS awake behind the map.
 */
@Singleton
class DeviceLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val stream: StateFlow<LocationUpdate?> = LocationUpdateBus.serviceRunning
        .flatMapLatest { serviceRunning ->
            if (serviceRunning) LocationUpdateBus.updates else merge(LocationUpdateBus.updates, systemUpdates())
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(IDLE_TIMEOUT_MS), null)

    /** Null until the first fix arrives; callers should then omit distance rather than fail. */
    fun observe(): Flow<LocationUpdate?> = stream

    /** The newest fix seen so far, for callers that cannot wait for one (`getLastLocation`). */
    val latest: LocationUpdate? get() = stream.value

    @SuppressLint("MissingPermission")
    private fun systemUpdates(): Flow<LocationUpdate> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        lastKnown(manager)?.let { trySend(it) }
        val listener = LocationListener { location -> trySend(location.toUpdate()) }
        val registered = runCatching { register(manager, listener) }.getOrDefault(false)
        awaitClose {
            if (registered) runCatching { manager?.removeUpdates(listener) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun register(manager: LocationManager?, listener: LocationListener): Boolean {
        var any = false
        for (provider in PROVIDERS) {
            if (manager?.isProviderEnabled(provider) != true) continue
            manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper())
            any = true
        }
        return any
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager?): LocationUpdate? = runCatching {
        PROVIDERS.firstNotNullOfOrNull { manager?.getLastKnownLocation(it) }?.toUpdate()
    }.getOrNull()

    private companion object {
        const val INTERVAL_MS = 10_000L
        const val MIN_DISTANCE_M = 0f
        const val IDLE_TIMEOUT_MS = 5_000L
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}

private fun Location.toUpdate() = LocationUpdate(
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracy,
    sampledAtUnixMs = System.currentTimeMillis(),
)
