package ir.vmessenger.core.map

import androidx.compose.runtime.Immutable
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.abs

/** What the camera is currently doing. The owner of the screen state decides; the map obeys. */
@Immutable
sealed interface MapCameraMode {

    /** Keep every marker on screen. Re-fits when the SET of marker ids changes, not per sample. */
    data object FitAll : MapCameraMode

    /** Track the device's own position (MapLibre `CameraMode.TRACKING`). */
    data object FollowMe : MapCameraMode

    /** The user took over by panning or zooming; the map is left alone. */
    data object Free : MapCameraMode
}

/**
 * A camera instruction. It is a value, not an event: the map applies it whenever it changes, so
 * a *repeat* of the same instruction (tapping "fit all" twice) must bump [token].
 */
@Immutable
data class CameraRequest(
    val mode: MapCameraMode = MapCameraMode.FitAll,
    val token: Int = 0,
    /** Centre this marker instead of fitting all of them. */
    val focusId: String? = null,
)

/** Turns markers into a MapLibre camera move. Pure: unit-reasonable and free of map state. */
internal object MapCamera {

    const val SINGLE_MARKER_ZOOM = 15.0
    const val FIT_PADDING_PX = 96

    /** Below this the bounding box is a point and `newLatLngBounds` would zoom to infinity. */
    private const val MIN_SPAN_DEGREES = 1e-4

    fun updateFor(markers: List<MapMarker>, focusId: String?): CameraUpdate? {
        val focused = focusId?.let { id -> markers.firstOrNull { it.id == id } }
        return when {
            focused != null -> CameraUpdateFactory.newLatLngZoom(focused.toLatLng(), SINGLE_MARKER_ZOOM)
            markers.isEmpty() -> null
            else -> fit(markers)
        }
    }

    private fun fit(markers: List<MapMarker>): CameraUpdate {
        val points = markers.map { it.toLatLng() }
        val latSpan = abs((points.maxOf { it.latitude }) - (points.minOf { it.latitude }))
        val lonSpan = abs((points.maxOf { it.longitude }) - (points.minOf { it.longitude }))
        return if (points.size < 2 || (latSpan < MIN_SPAN_DEGREES && lonSpan < MIN_SPAN_DEGREES)) {
            CameraUpdateFactory.newLatLngZoom(points.first(), SINGLE_MARKER_ZOOM)
        } else {
            val bounds = LatLngBounds.Builder().includes(points).build()
            CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX)
        }
    }
}

internal fun MapMarker.toLatLng(): LatLng = LatLng(latitude, longitude)
