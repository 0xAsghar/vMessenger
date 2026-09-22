package ir.vmessenger.feature.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.location.DeviceLocationProvider
import ir.vmessenger.core.location.LocationUpdateBus
import ir.vmessenger.core.map.MapContent
import ir.vmessenger.core.map.MapCoordinate
import ir.vmessenger.core.map.MapMarker
import ir.vmessenger.core.map.VmMapCallbacks
import ir.vmessenger.core.map.VmMapOptions
import ir.vmessenger.core.map.VmMapView
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

private val SHEET_PEEK_HEIGHT = 96.dp

/**
 * The map tab: a full-bleed map with floating controls over it and a bottom sheet listing the
 * contacts currently sharing their position.
 */
@Composable
fun MapRoute(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission(viewModel::onPermissionChanged)
    MapScreen(
        state = state,
        actions = rememberMapActions(viewModel, permission),
        permission = permission,
        locationSource = viewModel.locationSource,
    )
}

@Composable
private fun MapScreen(
    state: MapUiState,
    actions: MapActions,
    permission: LocationPermissionController,
    locationSource: DeviceLocationProvider,
    modifier: Modifier = Modifier,
) {
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    // The puck is the only thing on this screen that needs a *live* fix, so the registration is
    // scoped to it — leaving the tab releases it even if the flag is still set.
    if (state.showMyLocation) {
        DisposableEffect(Unit) {
            LocationUpdateBus.acquireLiveFix()
            onDispose { LocationUpdateBus.releaseLiveFix() }
        }
    }
    var pickerVisible by rememberSaveable { mutableStateOf(false) }
    val callbacks = remember(actions, scaffoldState, scope) {
        VmMapCallbacks(
            onMarkerClick = { contactId ->
                actions.onSelect(contactId)
                scope.launch { scaffoldState.bottomSheetState.partialExpand() }
            },
            onUserGesture = actions.onUserGesture,
            onStyleError = actions.onStyleError,
        )
    }
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = SHEET_PEEK_HEIGHT,
        sheetContent = { MapSheet(state, actions, permission) { pickerVisible = true } },
        modifier = modifier,
    ) {
        // The padding is deliberately ignored: the sheet floats over a map that fills the tab.
        Box(modifier = Modifier.fillMaxSize()) {
            VmMapView(
                content = rememberMapContent(state),
                options = VmMapOptions(
                    showMyLocation = state.showMyLocation,
                    darkStyle = isSystemInDarkTheme(),
                    styleToken = state.styleToken,
                ),
                callbacks = callbacks,
                modifier = Modifier.fillMaxSize(),
                locationProvider = locationSource,
            )
            MapOverlay(state = state, actions = actions, modifier = Modifier.safeDrawingPadding())
        }
    }
    if (pickerVisible) {
        SharePickerSheet(
            contacts = state.contacts,
            onSetAccess = actions.onSetAccess,
            onDismiss = { pickerVisible = false },
        )
    }
}

/** The floating layer: sharing pill, camera buttons and the offline banner. */
@Composable
private fun MapOverlay(state: MapUiState, actions: MapActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VmSpacing.md),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SharingPill(
                sharing = state.sharing,
                watcherCount = state.markers.size,
                modifier = Modifier.weight(1f, fill = false),
            )
            MapCameraButtons(
                onFollowMe = actions.onFollowMe,
                onFitAll = actions.onFitAll.takeIf { state.markers.isNotEmpty() },
            )
        }
        if (state.tilesError) TilesErrorBanner(onRetry = actions.onRetryTiles)
    }
}

/** Maps the screen state onto the map's own model; recomputed only when a marker changed. */
@Composable
private fun rememberMapContent(state: MapUiState): MapContent {
    val markers = remember(state.markers) {
        state.markers.map { marker ->
            MapMarker(
                id = marker.contactId,
                label = marker.name,
                seedHex = marker.seedHex,
                latitude = marker.latitude,
                longitude = marker.longitude,
                accuracyM = marker.accuracyM,
            )
        }.toImmutableList()
    }
    val self = state.myLocation?.let { MapCoordinate(it.latitude, it.longitude) }
    return remember(markers, state.camera, self, state.selectedPath) {
        MapContent(markers, state.camera, self, state.selectedPath)
    }
}

@Composable
private fun rememberMapActions(
    viewModel: MapViewModel,
    permission: LocationPermissionController,
): MapActions = remember(viewModel, permission) {
    MapActions(
        onToggleSharing = { permission.runWhenGranted(viewModel::toggleSharing) },
        onSetAccess = viewModel::setAccess,
        onSelect = viewModel::select,
        onFitAll = viewModel::fitAll,
        onFollowMe = { permission.runWhenGranted(viewModel::followMe) },
        onUserGesture = viewModel::onUserGesture,
        onStyleError = viewModel::onTilesError,
        onRetryTiles = viewModel::retryTiles,
    )
}

/** Anything that needs a fix asks for the permission first instead of failing silently. */
private fun LocationPermissionController.runWhenGranted(action: () -> Unit) {
    if (state == MapPermission.Granted) action() else request()
}
