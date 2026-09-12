package ir.vmessenger.feature.map

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.location.DeviceLocationProvider
import ir.vmessenger.core.location.LocationUpdate
import ir.vmessenger.core.map.CameraRequest
import ir.vmessenger.core.map.MapCameraMode
import ir.vmessenger.data.network.LocationSharingCoordinator
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.LocationSample
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.LocationAccessRepository
import ir.vmessenger.domain.repository.LocationRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three independent halves of the screen, combined once instead of eight times. */
private data class SharingSlice(
    val contacts: List<Contact>,
    val access: Map<String, Boolean>,
    val active: Boolean,
)

private data class LiveSlice(
    val incoming: Map<String, LocationSample>,
    val mine: LocationUpdate?,
)

private data class ScreenSlice(
    val permission: MapPermission,
    val camera: CameraRequest,
    val selectedContactId: String?,
    val tiles: TilesState,
    val hint: MapHint?,
)

/** Whether the basemap failed, plus the token that "try again" bumps to force a fresh fetch. */
private data class TilesState(val error: Boolean = false, val token: Int = 0)

/**
 * Everything the map tab shows, as one state.
 *
 * It is published with `WhileSubscribed`, so leaving the tab stops every database query, the
 * location listener and the incoming-sample flow within five seconds — the old view model
 * collected in `init` and kept them running for the whole life of the screen's view model.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val locationRepository: LocationRepository,
    private val locationAccessRepository: LocationAccessRepository,
    private val locationSharingCoordinator: LocationSharingCoordinator,
    private val deviceLocationProvider: DeviceLocationProvider,
) : ViewModel() {

    /** Handed to the map view so its puck subscribes to the one stream instead of a second engine. */
    val locationSource: DeviceLocationProvider get() = deviceLocationProvider

    private val permission = MutableStateFlow(MapPermission.Denied)
    private val camera = MutableStateFlow(CameraRequest())
    private val selected = MutableStateFlow<String?>(null)
    private val tiles = MutableStateFlow(TilesState())
    private val hint = MutableStateFlow<MapHint?>(null)

    private val sharingSlice = combine(
        contactRepository.observeContacts(),
        locationAccessRepository.observeAll(),
        // Only our own outgoing sharing drives the switch; someone sharing with us must not
        // make it read "on".
        locationRepository.observeIsSharing(),
        ::SharingSlice,
    )

    private val liveSlice = combine(
        locationRepository.observeIncomingLocations(),
        deviceLocationProvider.observe(),
        ::LiveSlice,
    )

    private val screenSlice = combine(permission, camera, selected, tiles, hint, ::ScreenSlice)

    val uiState: StateFlow<MapUiState> = combine(sharingSlice, liveSlice, screenSlice, ::buildState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), MapUiState())

    fun setAccess(contactId: String, granted: Boolean) {
        hint.value = null
        viewModelScope.launch { locationAccessRepository.setAccess(contactId, granted) }
    }

    /** Starts sharing with every contact the user ticked, or stops all of it. */
    fun toggleSharing() {
        viewModelScope.launch {
            if (uiState.value.sharing.active) {
                hint.value = null
                locationSharingCoordinator.stopAllSharing()
            } else {
                // The only start failure today is "no approved contact selected".
                val result = locationSharingCoordinator.startSharingToGrantedContacts()
                hint.value = if (result is AppResult.Error) MapHint.SelectContactFirst else null
            }
        }
    }

    /** Selecting a row (or tapping its pin) centres that contact and hands the camera over. */
    fun select(contactId: String?) {
        selected.value = contactId
        camera.update { CameraRequest(MapCameraMode.Free, it.token + 1, contactId) }
    }

    fun fitAll() {
        selected.value = null
        val mode = if (uiState.value.markers.isEmpty()) MapCameraMode.FollowMe else MapCameraMode.FitAll
        camera.update { CameraRequest(mode, it.token + 1) }
    }

    fun followMe() {
        selected.value = null
        camera.update { CameraRequest(MapCameraMode.FollowMe, it.token + 1) }
    }

    /** A pan or a pinch: the user owns the camera now, until a button says otherwise. */
    fun onUserGesture() {
        camera.update { current ->
            if (current.mode == MapCameraMode.Free) current else CameraRequest(MapCameraMode.Free, current.token)
        }
    }

    fun onPermissionChanged(state: MapPermission) {
        permission.value = state
    }

    fun onTilesError() {
        tiles.update { it.copy(error = true) }
    }

    fun retryTiles() {
        tiles.update { TilesState(error = false, token = it.token + 1) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

private fun buildState(sharing: SharingSlice, live: LiveSlice, screen: ScreenSlice): MapUiState {
    val approved = sharing.contacts.filter { it.isApproved && !it.blocked }
    val byId = approved.associateBy { it.id }
    val markers = live.incoming
        .mapNotNull { (contactId, sample) -> byId[contactId]?.let { marker(it, sample, live.mine) } }
        .sortedBy { it.name }
        .toImmutableList()
    val granted = approved.filter { sharing.access[it.id] == true }
    return MapUiState(
        permission = screen.permission,
        sharing = SharingState(sharing.active, granted.map { it.displayName }.toImmutableList()),
        markers = markers,
        contacts = approved.map { it.toAccess(sharing.access[it.id] == true) }.toImmutableList(),
        myLocation = live.mine?.let { MapPoint(it.latitude, it.longitude) },
        camera = screen.camera.forMarkers(markers.isNotEmpty()),
        selectedContactId = screen.selectedContactId.takeIf { id -> markers.any { it.contactId == id } },
        tilesError = screen.tiles.error,
        styleToken = screen.tiles.token,
        hint = screen.hint,
    )
}

/**
 * "Fit everything" with nothing to fit would leave the map at world zoom, so with no contact
 * sharing the camera follows this device instead.
 */
private fun CameraRequest.forMarkers(hasMarkers: Boolean): CameraRequest =
    if (mode == MapCameraMode.FitAll && !hasMarkers) copy(mode = MapCameraMode.FollowMe) else this

private fun marker(contact: Contact, sample: LocationSample, mine: LocationUpdate?): ContactMarker =
    ContactMarker(
        contactId = contact.id,
        name = contact.displayName,
        seedHex = contact.identityHash.toHex(),
        latitude = sample.latitude,
        longitude = sample.longitude,
        accuracyM = sample.accuracyM,
        lastUpdateLabel = VmDateFormat.relative(sample.sampledAtUnixMs),
        distanceM = mine?.let { distanceBetween(it, sample) },
    )

private fun Contact.toAccess(granted: Boolean) = ContactAccess(
    contactId = id,
    name = displayName,
    seedHex = identityHash.toHex(),
    granted = granted,
)

private fun distanceBetween(mine: LocationUpdate, sample: LocationSample): Float {
    val results = FloatArray(1)
    Location.distanceBetween(mine.latitude, mine.longitude, sample.latitude, sample.longitude, results)
    return results[0]
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
