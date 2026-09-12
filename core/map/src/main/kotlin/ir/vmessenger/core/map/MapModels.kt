package ir.vmessenger.core.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList

/**
 * One contact pin on the map.
 *
 * Everything here is a value type, so an [androidx.compose.runtime.Immutable] list of markers
 * compares structurally: the GeoJSON behind the map is rebuilt when — and only when — a marker
 * actually changed, never merely because the screen recomposed.
 */
@Immutable
data class MapMarker(
    val id: String,
    val label: String,
    /** Hex of the contact's identity hash; seeds the identicon and its colour. */
    val seedHex: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
) {
    /**
     * Identity of the *drawn bitmap*: colour seed plus label, never the position. A contact
     * that moves keeps its cached pin; only a rename (or a new contact) builds a new one.
     */
    val iconKey: String get() = "$seedHex@$label"
}

/**
 * What the map shows: the pins, and what the camera is asked to do with them. One value, so a
 * screen that recomposes without any of it changing cannot make the map do work.
 */
@Immutable
data class MapContent(
    val markers: ImmutableList<MapMarker>,
    val camera: CameraRequest = CameraRequest(),
)

/** Static rendering knobs; they change rarely, so they never invalidate marker or camera work. */
@Immutable
data class VmMapOptions(
    /** Draws the standard location puck. The caller must hold the location permission. */
    val showMyLocation: Boolean = false,
    val darkStyle: Boolean = false,
    /** False for the read-only mini-map embedded in other screens. */
    val interactive: Boolean = true,
    /** Bump to re-fetch the style after a network failure; the value itself means nothing. */
    val styleToken: Int = 0,
    /**
     * True keeps the GL surface alive for the whole activity, so leaving and re-entering a tab
     * costs nothing. False ties it to the current screen instead — the right choice for a map
     * embedded in a detail screen, and the reason two maps never fight over one cached view.
     */
    val persistent: Boolean = true,
)

/**
 * Map → caller events. Not a data class on purpose: lambdas have no meaningful equality, so
 * callers should `remember` one instance rather than rely on comparison.
 */
@Stable
class VmMapCallbacks(
    val onMarkerClick: (String) -> Unit = {},
    /** The user panned or zoomed; the caller is expected to switch the camera to Free. */
    val onUserGesture: () -> Unit = {},
    /** The style (or a tile request) failed to load; the fallback style stays on screen. */
    val onStyleError: () -> Unit = {},
)
