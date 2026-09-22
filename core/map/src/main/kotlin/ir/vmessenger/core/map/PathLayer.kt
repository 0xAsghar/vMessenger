package ir.vmessenger.core.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The route a contact shared during one sharing session, as a single GeoJSON line.
 *
 * Written only from [setPath] — the rule the marker source follows too — so a recomposition cannot
 * re-upload the geometry. Installed before [MarkerLayer] by its owner, which puts the line under
 * the pins instead of across their labels.
 *
 * Fewer than two points draws nothing: one sample is a position rather than a route, and the pin
 * already says where it is.
 */
internal class PathLayer(private val chrome: MarkerChrome) {

    private var style: Style? = null
    private var points: List<MapCoordinate> = emptyList()

    /** Called for every freshly loaded style: the previous style's sources and layers are gone. */
    fun attach(style: Style) {
        this.style = style
        install(style)
        push(style)
    }

    fun detach() {
        style = null
    }

    fun setPath(next: List<MapCoordinate>) {
        points = next
        style?.let { push(it) }
    }

    private fun install(style: Style) {
        if (style.getSourceAs<GeoJsonSource>(SOURCE_ID) != null) return
        style.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            LineLayer(LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.lineColor(chrome.accuracyStroke()),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }

    private fun push(style: Style) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(features()))
    }

    private fun features(): List<Feature> {
        if (points.size < MIN_PATH_POINTS) return emptyList()
        val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
        return listOf(Feature.fromGeometry(line))
    }

    private companion object {
        const val SOURCE_ID = "vm-path-source"
        const val LAYER_ID = "vm-path-line"
        const val LINE_WIDTH = 4f
        const val MIN_PATH_POINTS = 2
    }
}
