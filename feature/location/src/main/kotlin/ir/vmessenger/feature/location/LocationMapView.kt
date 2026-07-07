package ir.vmessenger.feature.location

import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ir.vmessenger.domain.model.LocationSample
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val MAP_STYLE = "https://demotiles.maplibre.org/style.json"
private const val SOURCE_ID = "location-samples"
private const val LAYER_ID = "location-samples-layer"
private const val DEFAULT_ZOOM = 12.0

@Composable
fun LocationMapView(
    samples: Map<String, LocationSample>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context)
    }
    DisposableEffect(mapView) {
        mapView.onStart()
        onDispose {
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.getMapAsync { map ->
                val points = samples.values.filter { it.sampledAtUnixMs > 0 }
                map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                    updateMarkers(style, points)
                    val first = points.firstOrNull()
                    if (first != null) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(first.latitude, first.longitude))
                            .zoom(DEFAULT_ZOOM)
                            .build()
                    } else {
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(35.6892, 51.3890), DEFAULT_ZOOM),
                        )
                    }
                }
            }
        },
    )
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
