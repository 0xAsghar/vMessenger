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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's own position — one consumer per process.
 *
 * While [LocationService] runs it is the only listener and this class just relays its bus. With the
 * service stopped it registers a [LocationManager] listener of its own *only* while some screen has
 * asked for a live fix via [LocationUpdateBus.acquireLiveFix]; otherwise it seeds the last known
 * position and registers nothing.
 *
 * That gate is the point. Collecting this flow used to be enough to turn GPS on, so switching
 * location sharing off merely moved the registration out of the foreground service and into the app
 * process — the hardware never stood down. Callers that only need a distance label must collect
 * without acquiring, and tolerate a stale or null fix.
 */
/** The two inputs that decide whether a registration is warranted, combined once. */
private data class Demand(val serviceRunning: Boolean, val holders: Int)

@Singleton
class DeviceLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val stream: StateFlow<LocationUpdate?> =
        combine(LocationUpdateBus.serviceRunning, LocationUpdateBus.liveFixHolders, ::Demand)
            .flatMapLatest { demand ->
                when {
                    // The service owns the registration; adding ours would be the second listener.
                    demand.serviceRunning -> LocationUpdateBus.updates
                    demand.holders > 0 -> merge(LocationUpdateBus.updates, systemUpdates())
                    else -> merge(LocationUpdateBus.updates, lastKnownOnce())
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(IDLE_TIMEOUT_MS), null)

    /** Null until the first fix arrives; callers should then omit distance rather than fail. */
    fun observe(): Flow<LocationUpdate?> = stream

    /** The newest fix seen so far, for callers that cannot wait for one (`getLastLocation`). */
    val latest: LocationUpdate? get() = stream.value

    /** Seeds a fix for distance labels without ever touching the hardware. */
    @SuppressLint("MissingPermission")
    private fun lastKnownOnce(): Flow<LocationUpdate> = flow {
        lastKnown(context.getSystemService(LocationManager::class.java))?.let { emit(it) }
    }

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
