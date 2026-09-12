package ir.vmessenger.core.map

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.maps.MapView

/** Everything a rendered map needs that must survive recomposition, in one remembered value. */
@Stable
internal class MapBinding(
    val controller: MapController,
    val mapView: MapView,
    private val savedState: Bundle,
    val owner: LifecycleOwner,
) {
    /** Writes the camera into the `rememberSaveable` bundle that outlives this composition. */
    fun save() {
        runCatching { mapView.onSaveInstanceState(savedState) }
    }
}
