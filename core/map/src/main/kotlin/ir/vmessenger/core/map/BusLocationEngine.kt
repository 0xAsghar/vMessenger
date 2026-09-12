package ir.vmessenger.core.map

import android.app.PendingIntent
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import ir.vmessenger.core.location.DeviceLocationProvider
import ir.vmessenger.core.location.LocationUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds MapLibre's location puck from the app's own [DeviceLocationProvider].
 *
 * Without it MapLibre spins up its *default* engine, which means a third `LocationManager`
 * registration next to the foreground service and the provider — three consumers, three wake-ups.
 * Here the puck is just another subscriber to the one stream the app already has.
 */
class BusLocationEngine(private val provider: DeviceLocationProvider) : LocationEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = ConcurrentHashMap<LocationEngineCallback<LocationEngineResult>, Job>()

    override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
        val last = provider.latest
        if (last == null) {
            callback.onFailure(IllegalStateException("no location fix yet"))
        } else {
            callback.onSuccess(LocationEngineResult.create(last.toAndroidLocation()))
        }
    }

    /**
     * [looper] is ignored on purpose: the collector already runs on the main dispatcher, which is
     * the thread MapLibre's animators require.
     */
    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        callback: LocationEngineCallback<LocationEngineResult>,
        looper: Looper?,
    ) {
        val job = scope.launch {
            provider.observe().filterNotNull().collect { update ->
                callback.onSuccess(LocationEngineResult.create(update.toAndroidLocation()))
            }
        }
        jobs.put(callback, job)?.cancel()
    }

    override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
        jobs.remove(callback)?.cancel()
    }

    /** Background delivery through a PendingIntent is not offered; the map only draws in front. */
    override fun requestLocationUpdates(request: LocationEngineRequest, pendingIntent: PendingIntent?) = Unit

    override fun removeLocationUpdates(pendingIntent: PendingIntent?) = Unit
}

private fun LocationUpdate.toAndroidLocation(): Location = Location(PROVIDER_NAME).also {
    it.latitude = latitude
    it.longitude = longitude
    it.accuracy = accuracyM
    it.time = sampledAtUnixMs
    it.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
}

private const val PROVIDER_NAME = "vmessenger"
