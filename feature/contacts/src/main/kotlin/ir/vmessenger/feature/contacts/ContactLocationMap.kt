package ir.vmessenger.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import ir.vmessenger.core.designsystem.component.VmSmallFab
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.core.map.CameraRequest
import ir.vmessenger.core.map.MapCameraMode
import ir.vmessenger.core.map.MapContent
import ir.vmessenger.core.map.VmMapCallbacks
import ir.vmessenger.core.map.VmMapOptions
import ir.vmessenger.core.map.VmMapView
import kotlinx.collections.immutable.persistentListOf

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The map on a contact's screen, which is a picture in [ContactLocationCard] and, once the person
 * opens it, the whole screen ([ContactLocationFullScreen]) with its gestures on.
 *
 * They are two maps in turn, never one view moved from the card to the full screen: the card's is
 * dropped while the full screen is open and made again when it shuts, and each is made at the size
 * it keeps. A live GL view moved to a parent of another size kept its old size about one open in
 * five (or stayed blank, with a texture view), so it is not moved. What the two share is this
 * holder: whether the map is open, and whether the camera still follows the contact.
 */
@Stable
internal class ContactLocationMap(expanded: Boolean = false) {

    /** Whether the map fills the screen. */
    var expanded by mutableStateOf(expanded)
        private set

    /**
     * Whether the camera still follows the contact. It does until the person moves the map with a
     * gesture; from then on a new position moves the pin and leaves the camera where it is.
     */
    var following by mutableStateOf(true)
        private set

    private val callbacks = VmMapCallbacks(onUserGesture = ::onUserGesture)

    fun expand() {
        expanded = true
    }

    /** Back to the card, centred on the contact again wherever the person had taken the map. */
    fun collapse() {
        expanded = false
        following = true
    }

    fun onUserGesture() {
        following = false
    }

    /** The map for the card or for the full screen: the one that is not showing is not composed. */
    @Composable
    fun Map(location: ContactLocation) {
        val content = remember(location, following) { locationMapContent(location, following) }
        VmMapView(
            content = content,
            options = VmMapOptions(darkStyle = isSystemInDarkTheme(), interactive = expanded, persistent = false),
            callbacks = callbacks,
        )
    }

    companion object {
        /** Whether the map is open survives a rotation; nothing else about it does. */
        val Saver: Saver<ContactLocationMap, Boolean> =
            Saver(save = { it.expanded }, restore = { ContactLocationMap(expanded = it) })
    }
}

@Composable
internal fun rememberContactLocationMap(): ContactLocationMap =
    rememberSaveable(saver = ContactLocationMap.Saver) { ContactLocationMap() }

/**
 * What the contact's map shows: their pin, the route, and a camera that centres on them for each new
 * position, until [following] ends because the person moved the map themselves. Then the camera is
 * left alone, so a live position cannot pull the map out from under their finger.
 */
internal fun locationMapContent(location: ContactLocation, following: Boolean): MapContent = MapContent(
    markers = persistentListOf(location.marker),
    path = location.path,
    camera = if (following) {
        // Keyed to the sample, so each new position re-centres the camera without touching it otherwise.
        CameraRequest(
            mode = MapCameraMode.FitAll,
            token = (location.sampledAtUnixMs / MILLIS_PER_SECOND).toInt(),
            focusId = location.marker.id,
        )
    } else {
        CameraRequest(mode = MapCameraMode.Free)
    },
)

/**
 * The contact's map over the whole screen, with a cross in its top right corner that shuts it again.
 *
 * It is laid over the screen's own frame, title bar and system bars included, so the cross keeps
 * inside the system bars while the map does not.
 */
@Composable
internal fun ContactLocationFullScreen(
    location: ContactLocation,
    map: ContactLocationMap,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.contact_detail_location_map, location.marker.label)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VmTheme.colors.bgCanvas)
            .semantics { contentDescription = description },
    ) {
        map.Map(location)
        MapCorner(alignment = Alignment.TopEnd, modifier = Modifier.safeDrawingPadding().padding(VmSpacing.md)) {
            VmSmallFab(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.contact_detail_location_collapse),
                onClick = map::collapse,
            )
        }
    }
}

/**
 * A control floating over one of the map's right-hand corners.
 *
 * Right means right in both languages: the map itself is not mirrored, so the controls on it are
 * not either. The layout direction is pinned to left to right here, which makes `End` the physical
 * right edge.
 */
@Composable
internal fun MapCorner(alignment: Alignment, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = alignment) { content() }
    }
}
