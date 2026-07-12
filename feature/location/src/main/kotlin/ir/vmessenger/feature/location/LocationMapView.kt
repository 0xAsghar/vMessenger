package ir.vmessenger.feature.location

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ir.vmessenger.domain.model.LocationSample
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

// Free vector style with full street data and no API key (openfreemap.org).
private const val PRIMARY_STYLE = "https://tiles.openfreemap.org/styles/liberty"

// Minimal world outline style hosted by MapLibre; last-resort fallback.
private const val FALLBACK_STYLE = "https://demotiles.maplibre.org/style.json"

private const val SOURCE_ID = "location-samples"
private const val LAYER_ID = "location-samples-layer"
private const val DEFAULT_ZOOM = 14.0
private const val MULTI_POINT_PADDING_PX = 96
private const val TRACKING_ZOOM_ATTEMPTS = 5
private const val TRACKING_ZOOM_DELAY_MS = 1_500L

/** Keeps map/style references and the latest samples across recompositions. */
private class MapStateHolder {
    var map: MapLibreMap? = null
    var view: MapView? = null
    var style: Style? = null
    var pendingSamples: List<LocationSample> = emptyList()
    var cameraInitialized = false
    var fallbackApplied = false
    var myLocationRequested = false
}

@Composable
fun LocationMapView(
    samples: Map<String, LocationSample>,
    modifier: Modifier = Modifier,
    showMyLocation: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { MapStateHolder() }
    val mapView = remember {
        // MapView requires onCreate before use; without it the surface never
        // initializes and getMapAsync callbacks never fire.
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.addOnDidFailLoadingMapListener {
            val map = holder.map
            if (!holder.fallbackApplied && map != null) {
                holder.fallbackApplied = true
                map.setStyle(Style.Builder().fromUri(FALLBACK_STYLE)) { style ->
                    holder.style = style
                    renderSamples(holder, context)
                }
            }
        }
        holder.view = mapView
        mapView.getMapAsync { map ->
            holder.map = map
            map.setStyle(Style.Builder().fromUri(PRIMARY_STYLE)) { style ->
                holder.style = style
                renderSamples(holder, context)
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.map = null
            holder.view = null
            holder.style = null
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
        update = {
            holder.pendingSamples = samples.values.filter { it.sampledAtUnixMs > 0 }
            holder.myLocationRequested = showMyLocation
            renderSamples(holder, context)
        },
    )
}

private fun renderSamples(holder: MapStateHolder, context: Context) {
    val style = holder.style ?: return
    if (!style.isFullyLoaded) return
    if (holder.myLocationRequested) {
        holder.map?.let { enableOwnLocationDot(it, style, context, holder) }
    }
    val points = holder.pendingSamples
    updateMarkers(style, points)
    if (points.isNotEmpty() && !holder.cameraInitialized) {
        holder.cameraInitialized = true
        holder.map?.let { moveCameraTo(it, points) }
    }
}

/**
 * Shows the device's own position as the standard blue location puck so the map
 * is immediately alive after the location permission is granted, even before
 * any share has produced samples. Caller must hold the location permission.
 */
@SuppressLint("MissingPermission")
private fun enableOwnLocationDot(
    map: MapLibreMap,
    style: Style,
    context: Context,
    holder: MapStateHolder,
) {
    runCatching {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
            component.isLocationComponentEnabled = true
            // Follow own position while nothing is shared; once shared samples
            // exist the camera is fitted to them instead.
            if (holder.pendingSamples.isEmpty()) {
                component.cameraMode = CameraMode.TRACKING
                scheduleTrackingZoom(holder, attempt = 0)
            } else {
                component.cameraMode = CameraMode.NONE
            }
        }
    }
}

/**
 * zoomWhileTracking is a no-op until the location engine delivers its first fix,
 * so retry a few times after activation instead of staying at world zoom.
 */
private fun scheduleTrackingZoom(holder: MapStateHolder, attempt: Int) {
    if (attempt >= TRACKING_ZOOM_ATTEMPTS) return
    holder.view?.postDelayed({
        runCatching {
            val map = holder.map ?: return@postDelayed
            val component = map.locationComponent
            if (!component.isLocationComponentActivated) return@postDelayed
            if (component.cameraMode != CameraMode.TRACKING) return@postDelayed
            if (component.lastKnownLocation != null && map.cameraPosition.zoom < DEFAULT_ZOOM - 1) {
                component.zoomWhileTracking(DEFAULT_ZOOM)
            } else if (component.lastKnownLocation == null) {
                scheduleTrackingZoom(holder, attempt + 1)
            }
        }
    }, TRACKING_ZOOM_DELAY_MS)
}

private fun moveCameraTo(map: MapLibreMap, points: List<LocationSample>) {
    runCatching {
        if (map.locationComponent.isLocationComponentActivated) {
            map.locationComponent.cameraMode = CameraMode.NONE
        }
    }
    if (points.size == 1) {
        val single = points.first()
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(single.latitude, single.longitude), DEFAULT_ZOOM),
        )
        return
    }
    val bounds = LatLngBounds.Builder()
        .apply { points.forEach { include(LatLng(it.latitude, it.longitude)) } }
        .build()
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, MULTI_POINT_PADDING_PX))
}

private fun updateMarkers(style: Style, points: List<LocationSample>) {
    val features = points.map { sample ->
        Feature.fromGeometry(Point.fromLngLat(sample.longitude, sample.latitude))
    }
    val collection = FeatureCollection.fromFeatures(features)
    val existing = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
    if (existing != null) {
        existing.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ID, collection))
        style.addLayer(
            CircleLayer(LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(Color.parseColor("#E53935")),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            ),
        )
    }
}
