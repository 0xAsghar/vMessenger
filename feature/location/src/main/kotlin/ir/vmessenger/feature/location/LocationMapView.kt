package ir.vmessenger.feature.location

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

/** Keeps map/style references and the latest samples across recompositions. */
private class MapStateHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var pendingSamples: List<LocationSample> = emptyList()
    var cameraInitialized = false
    var fallbackApplied = false
}

@Composable
fun LocationMapView(
    samples: Map<String, LocationSample>,
    modifier: Modifier = Modifier,
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
                    renderSamples(holder)
                }
            }
        }
        mapView.getMapAsync { map ->
            holder.map = map
            map.setStyle(Style.Builder().fromUri(PRIMARY_STYLE)) { style ->
                holder.style = style
                renderSamples(holder)
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.map = null
            holder.style = null
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
        update = {
            holder.pendingSamples = samples.values.filter { it.sampledAtUnixMs > 0 }
            renderSamples(holder)
        },
    )
}

private fun renderSamples(holder: MapStateHolder) {
    val style = holder.style ?: return
    if (!style.isFullyLoaded) return
    val points = holder.pendingSamples
    updateMarkers(style, points)
    if (points.isNotEmpty() && !holder.cameraInitialized) {
        holder.cameraInitialized = true
        holder.map?.let { moveCameraTo(it, points) }
    }
}

private fun moveCameraTo(map: MapLibreMap, points: List<LocationSample>) {
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
