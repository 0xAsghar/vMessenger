package ir.vmessenger.core.map

import android.content.Context
import android.graphics.RectF
import kotlinx.coroutines.CoroutineScope
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The one owner of a MapLibre surface: style, markers, camera and puck.
 *
 * Everything here is imperative and lives outside composition on purpose. [VmMapView] only feeds
 * it from keyed effects, so a recomposition alone never touches the map.
 */
internal class MapController(
    private val context: Context,
    private val mapView: MapView,
    bitmaps: MarkerBitmaps,
    engine: LocationEngine?,
    scope: CoroutineScope,
) {
    /** Reassigned on every recomposition; the listeners below always read the current one. */
    var callbacks: VmMapCallbacks = VmMapCallbacks()

    private val layer = MarkerLayer(bitmaps)
    private val puck = MapPuck(context, engine, scope)
    private val tapSlop = TAP_SLOP_DP * context.resources.displayMetrics.density

    private var map: MapLibreMap? = null
    private var desiredStyle: String = MapStyle.LIGHT
    private var desiredToken: Int = 0
    private var appliedStyle: String? = null
    private var appliedToken: Int = -1
    private var interactive: Boolean = true
    private var styleGeneration: Int = 0
    private var fallbackApplied: Boolean = false
    private var onStyleReady: () -> Unit = {}

    private val styleLoadedListener = Style.OnStyleLoaded { style ->
        layer.attach(style)
        styleGeneration++
        onStyleReady()
    }

    private val failListener = MapView.OnDidFailLoadingMapListener { applyFallbackStyle() }

    private val gestureListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) callbacks.onUserGesture()
    }

    private val clickListener = MapLibreMap.OnMapClickListener { point ->
        val target = map
        val screen = target?.projection?.toScreenLocation(point)
        val hit = if (target == null || screen == null) {
            null
        } else {
            target.queryRenderedFeatures(
                RectF(screen.x - tapSlop, screen.y - tapSlop, screen.x + tapSlop, screen.y + tapSlop),
                MarkerLayer.PIN_LAYER_ID,
            ).firstNotNullOfOrNull { feature -> feature.getStringProperty(MarkerLayer.PROP_ID) }
        }
        hit?.let(callbacks.onMarkerClick)
        hit != null
    }

    fun start(onStyleReady: () -> Unit) {
        this.onStyleReady = onStyleReady
        mapView.addOnDidFailLoadingMapListener(failListener)
        mapView.getMapAsync(::onMapReady)
    }

    /** Leaves the (cached) view alive; only this screen's wiring goes away. */
    fun stop() {
        onStyleReady = {}
        mapView.removeOnDidFailLoadingMapListener(failListener)
        map?.removeOnCameraMoveStartedListener(gestureListener)
        map?.removeOnMapClickListener(clickListener)
        puck.disable(map)
        layer.detach()
        map = null
    }

    fun applyStyle(dark: Boolean, token: Int) {
        desiredStyle = MapStyle.forTheme(dark)
        desiredToken = token
        map?.let(::syncStyle)
    }

    fun setInteractive(enabled: Boolean) {
        interactive = enabled
        map?.uiSettings?.setAllGesturesEnabled(enabled)
    }

    fun setMarkers(markers: List<MapMarker>) {
        layer.setMarkers(markers)
    }

    fun updateMyLocation(show: Boolean, follow: Boolean) {
        map?.let { puck.sync(it, PuckRequest(show, follow, styleGeneration)) }
    }

    /** Following is the puck's job ([updateMyLocation]); this only handles explicit moves. */
    fun applyCamera(request: CameraRequest, markers: List<MapMarker>, self: MapCoordinate? = null) {
        val target = map ?: return
        val move = when (request.mode) {
            MapCameraMode.FitAll -> MapCamera.updateFor(markers, request.focusId, self)
            MapCameraMode.Free -> request.focusId?.let { MapCamera.updateFor(markers, it, self) }
            MapCameraMode.FollowMe -> null
        }
        move?.let(target::animateCamera)
    }

    private fun onMapReady(ready: MapLibreMap) {
        map = ready
        ready.addOnCameraMoveStartedListener(gestureListener)
        ready.addOnMapClickListener(clickListener)
        ready.uiSettings.setAllGesturesEnabled(interactive)
        syncStyle(ready)
    }

    /** Re-uses an already loaded style (returning to the tab) instead of re-downloading it. */
    private fun syncStyle(target: MapLibreMap) {
        val loaded = target.style
        val reusable = appliedStyle == desiredStyle && appliedToken == desiredToken
        if (reusable && loaded != null && loaded.isFullyLoaded) {
            styleLoadedListener.onStyleLoaded(loaded)
            return
        }
        appliedStyle = desiredStyle
        appliedToken = desiredToken
        fallbackApplied = false
        target.setStyle(Style.Builder().fromUri(desiredStyle), styleLoadedListener)
    }

    /**
     * A failure always raises the banner, but the offline style is only swapped in when nothing
     * loaded at all — a single failed tile must not throw away the real basemap.
     */
    private fun applyFallbackStyle() {
        callbacks.onStyleError()
        val target = map
        if (fallbackApplied || target == null || target.style?.isFullyLoaded == true) return
        fallbackApplied = true
        appliedStyle = MapStyle.FALLBACK
        target.setStyle(Style.Builder().fromUri(MapStyle.FALLBACK), styleLoadedListener)
    }

    private companion object {
        const val TAP_SLOP_DP = 12f
    }
}
