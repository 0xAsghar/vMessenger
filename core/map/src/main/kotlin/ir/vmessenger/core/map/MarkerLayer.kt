package ir.vmessenger.core.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.max

/**
 * Every contact pin as ONE GeoJSON source with two layers on top of it: an accuracy circle and
 * the symbol carrying the pre-rendered pin bitmap.
 *
 * The source is written only from [setMarkers], which the screen calls when the marker *data*
 * changed — never from an `AndroidView(update = …)` block, which would re-upload the whole
 * feature collection on every recomposition of the parent.
 */
internal class MarkerLayer(private val bitmaps: MarkerBitmaps) {

    private var style: Style? = null
    private val registeredImages = mutableSetOf<String>()
    private var markers: List<MapMarker> = emptyList()

    /** Called for every freshly loaded style: the old style's sources, layers and images are gone. */
    fun attach(style: Style) {
        this.style = style
        registeredImages.clear()
        install(style)
        push(style)
    }

    fun detach() {
        style = null
        registeredImages.clear()
    }

    fun setMarkers(next: List<MapMarker>) {
        markers = next
        style?.let { push(it) }
    }

    private fun install(style: Style) {
        if (style.getSourceAs<GeoJsonSource>(SOURCE_ID) != null) return
        style.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            CircleLayer(ACCURACY_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(accuracyRadius()),
                PropertyFactory.circleColor(bitmaps.chrome.accuracyFill()),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeColor(bitmaps.chrome.accuracyStroke()),
            ),
        )
        style.addLayer(
            SymbolLayer(PIN_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get(PROP_ICON)),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    private fun push(style: Style) {
        if (!style.isFullyLoaded) return
        val features = markers.map { marker -> feature(style, marker) }
        style.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun feature(style: Style, marker: MapMarker): Feature {
        val image = bitmaps.imageId(marker)
        if (registeredImages.add(image)) style.addImage(image, bitmaps.bitmap(marker))
        return Feature.fromGeometry(Point.fromLngLat(marker.longitude, marker.latitude)).apply {
            addStringProperty(PROP_ID, marker.id)
            addStringProperty(PROP_ICON, image)
            addNumberProperty(PROP_ACCURACY, accuracyAtZoomZero(marker))
        }
    }

    /**
     * Accuracy in *style pixels at zoom 0*. MapLibre then scales it with
     * `interpolate(exponential(2), zoom, …)`, which over stops 0..22 is exactly `value * 2^zoom`,
     * so the circle keeps covering the same patch of ground at every zoom level.
     */
    private fun accuracyAtZoomZero(marker: MapMarker): Double {
        val metersPerPixel = EQUATOR_METERS_PER_PIXEL * max(cos(Math.toRadians(marker.latitude)), MIN_COS)
        return marker.accuracyM / metersPerPixel
    }

    private fun accuracyRadius(): Expression = Expression.interpolate(
        Expression.exponential(2f),
        Expression.zoom(),
        Expression.stop(0, Expression.get(PROP_ACCURACY)),
        Expression.stop(MAX_ZOOM, Expression.product(Expression.get(PROP_ACCURACY), Expression.literal(MAX_SCALE))),
    )

    companion object {
        const val PIN_LAYER_ID = "vm-contact-pins"
        const val ACCURACY_LAYER_ID = "vm-contact-accuracy"
        const val SOURCE_ID = "vm-contacts"
        const val PROP_ID = "vm-id"

        private const val PROP_ICON = "vm-icon"
        private const val PROP_ACCURACY = "vm-accuracy"
        private const val MAX_ZOOM = 22
        private const val MAX_SCALE = 4_194_304.0
        private const val EQUATOR_METERS_PER_PIXEL = 78_271.517
        private const val MIN_COS = 0.01
    }
}
