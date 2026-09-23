package ir.vmessenger.feature.contacts

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.core.map.CameraRequest
import ir.vmessenger.core.map.MapCameraMode
import ir.vmessenger.core.map.MapContent
import ir.vmessenger.core.map.MapMarker
import ir.vmessenger.core.map.VmMapCallbacks
import ir.vmessenger.core.map.VmMapOptions
import ir.vmessenger.core.map.VmMapView
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.LocationSample
import kotlinx.collections.immutable.persistentListOf

private val MiniMapHeight = 200.dp
private const val MILLIS_PER_SECOND = 1_000L

/** A contact's shared position as the detail screen draws it: their pin, and when it was taken. */
@Immutable
data class ContactLocation(
    val marker: MapMarker,
    val sampledAtUnixMs: Long,
)

internal fun LocationSample.toContactLocation(contact: Contact) = ContactLocation(
    marker = MapMarker(
        id = contact.id,
        label = contact.displayName,
        seedHex = contact.identityHash.joinToString("") { "%02x".format(it) },
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
    ),
    sampledAtUnixMs = sampledAtUnixMs,
)

/**
 * Where a contact is, on the contact's own screen, while they share their position with us.
 *
 * Until now the only place to see it was the map tab, and the detail screen offered nothing but
 * the switch for the opposite direction. The map here is a picture, not a second map tab: it
 * follows each new sample, takes no gestures, and lives and dies with this screen rather than
 * borrowing the tab's cached view.
 */
@Composable
internal fun ContactLocationCard(location: ContactLocation, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.contact_detail_location_map, location.marker.label)
    // Keyed to the sample, so each new position re-centres the camera without touching it otherwise.
    val content = remember(location) {
        MapContent(
            markers = persistentListOf(location.marker),
            camera = CameraRequest(
                mode = MapCameraMode.FitAll,
                token = (location.sampledAtUnixMs / MILLIS_PER_SECOND).toInt(),
                focusId = location.marker.id,
            ),
        )
    }
    val callbacks = remember { VmMapCallbacks() }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.contact_detail_location_section))
        VmSurface(
            shape = VmShapes.card,
            modifier = Modifier
                .padding(horizontal = VmSpacing.lg)
                .fillMaxWidth()
                .height(MiniMapHeight),
        ) {
            Box {
                VmMapView(
                    content = content,
                    options = VmMapOptions(
                        darkStyle = isSystemInDarkTheme(),
                        interactive = false,
                        persistent = false,
                    ),
                    callbacks = callbacks,
                )
                // Over the map, so a drag that starts on it still scrolls the screen: the map view
                // would otherwise take the touch even with its own gestures switched off.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .semantics { contentDescription = description }
                        .pointerInput(Unit) {},
                )
            }
        }
        VmText(
            text = stringResource(
                R.string.contact_detail_location_updated,
                VmDateFormat.relative(location.sampledAtUnixMs),
            ),
            style = VmTheme.typography.bodySm,
            color = VmTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xs),
        )
    }
}
