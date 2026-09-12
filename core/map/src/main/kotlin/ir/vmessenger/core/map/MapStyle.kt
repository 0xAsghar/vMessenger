package ir.vmessenger.core.map

/**
 * Vector styles for [VmMapView]. All three are key-free and were verified to resolve
 * (HTTP 200, `application/json`) when this file was written.
 */
object MapStyle {

    /** Full street detail, light palette. */
    const val LIGHT = "https://tiles.openfreemap.org/styles/liberty"

    /** Same data, dark palette (`background-color: #45516E`). */
    const val DARK = "https://tiles.openfreemap.org/styles/fiord"

    /**
     * Country outlines only, hosted by MapLibre. Applied when the primary style fails so the
     * contact pins still have something to sit on while the network is down.
     */
    const val FALLBACK = "https://demotiles.maplibre.org/style.json"

    fun forTheme(dark: Boolean): String = if (dark) DARK else LIGHT
}
