package ir.vmessenger.core.map

import android.annotation.SuppressLint
import android.content.Context
import ir.vmessenger.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/** What the puck should be doing right now, as one value so [MapPuck.sync] stays a two-argument call. */
internal data class PuckRequest(
    val show: Boolean,
    val follow: Boolean,
    /** Bumped by every style load; the component has to be re-activated against the new style. */
    val styleGeneration: Int,
)

/**
 * The blue "my position" dot.
 *
 * It is always activated with [BusLocationEngine], never with MapLibre's default engine, so the
 * puck shares the one location stream the app already runs.
 */
internal class MapPuck(
    private val context: Context,
    private val engine: LocationEngine?,
    private val scope: CoroutineScope,
) {
    private var activatedGeneration = -1
    private var zoomJob: Job? = null

    fun sync(map: MapLibreMap, request: PuckRequest) {
        val style = map.style
        runCatching {
            if (style == null || !style.isFullyLoaded) return
            apply(map.locationComponent, map, style, request)
        }.onFailure { AppLogger.warn("Map", "location puck unavailable: ${it.message}") }
    }

    fun disable(map: MapLibreMap?) {
        zoomJob?.cancel()
        zoomJob = null
        runCatching {
            map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.isLocationComponentEnabled = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun apply(component: LocationComponent, map: MapLibreMap, style: Style, request: PuckRequest) {
        if (!request.show) {
            disable(map)
            return
        }
        if (activatedGeneration != request.styleGeneration) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(engine == null)
                    .also { builder -> engine?.let { builder.locationEngine(it) } }
                    .build(),
            )
            activatedGeneration = request.styleGeneration
        }
        component.isLocationComponentEnabled = true
        component.cameraMode = if (request.follow) CameraMode.TRACKING else CameraMode.NONE
        if (request.follow) scheduleFollowZoom(map, component)
    }

    /**
     * `zoomWhileTracking` does nothing until the engine has delivered a fix, so the zoom is
     * retried a few times. A coroutine, not `postDelayed`: it dies with the screen's scope
     * instead of firing into a destroyed map.
     */
    private fun scheduleFollowZoom(map: MapLibreMap, component: LocationComponent) {
        zoomJob?.cancel()
        zoomJob = scope.launch {
            repeat(ZOOM_ATTEMPTS) {
                if (component.lastKnownLocation != null) {
                    if (map.cameraPosition.zoom < FOLLOW_ZOOM) component.zoomWhileTracking(FOLLOW_ZOOM)
                    return@launch
                }
                delay(ZOOM_RETRY_MS)
            }
        }
    }

    private companion object {
        const val FOLLOW_ZOOM = 16.0
        const val ZOOM_ATTEMPTS = 6
        const val ZOOM_RETRY_MS = 1_500L
    }
}
