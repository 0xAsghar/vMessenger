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
import ir.vmessenger.core.map.MapCoordinate
import ir.vmessenger.core.map.MapMarker
import ir.vmessenger.core.map.VmMapCallbacks
import ir.vmessenger.core.map.VmMapOptions
import ir.vmessenger.core.map.VmMapView
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.LocationSample
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val MiniMapHeight = 200.dp
private const val MILLIS_PER_SECOND = 1_000L

/**
 * A contact's position as the detail screen draws it: their pin, when it was taken, whether they are
 * sharing right now, and the route they shared (oldest first).
 */
@Immutable
data class ContactLocation(
    val marker: MapMarker,
    val sampledAtUnixMs: Long,
    val live: Boolean = true,
    val path: ImmutableList<MapCoordinate> = persistentListOf(),
)

/** One position in a contact's location history. */
@Immutable
data class LocationHistoryEntry(
    val sampledAtUnixMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
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

/** The live position when they share now, otherwise the last one they shared; with the route either way. */
internal fun locationOf(data: ContactDetailData, contact: Contact): ContactLocation? {
    val latest = data.sharedLocation ?: data.history.firstOrNull() ?: return null
    val path = data.history.asReversed().map { MapCoordinate(it.latitude, it.longitude) }
    return latest.toContactLocation(contact).copy(live = data.sharedLocation != null, path = path.toImmutableList())
}

/**
 * The history as changes of place: [samples] (newest first) with each stay collapsed into one entry,
 * at the stay's newest position and the time they got there. A sample within [MIN_MOVE_M] of the
 * entry after it is the same place, so standing still for an hour is one row, not a thousand.
 */
internal fun locationChanges(samples: List<LocationSample>): List<LocationHistoryEntry> {
    val changes = ArrayList<LocationHistoryEntry>()
    for (sample in samples) {
        val last = changes.lastOrNull()
        val samePlace = last != null &&
            metersBetween(last.latitude, last.longitude, sample.latitude, sample.longitude) < MIN_MOVE_M
        if (last != null && samePlace) {
            changes[changes.lastIndex] = last.copy(sampledAtUnixMs = sample.sampledAtUnixMs)
        } else {
            changes += LocationHistoryEntry(sample.sampledAtUnixMs, sample.latitude, sample.longitude, sample.accuracyM)
        }
    }
    return changes
}

/** Great-circle distance; plain Kotlin so it runs in unit tests. */
private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

private const val MIN_MOVE_M = 20.0
private const val EARTH_RADIUS_M = 6_371_000.0

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
            path = location.path,
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
                if (location.live) R.string.contact_detail_location_updated else R.string.contact_detail_location_last,
                VmDateFormat.relative(location.sampledAtUnixMs),
            ),
            style = VmTheme.typography.bodySm,
            color = VmTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xs),
        )
    }
}
