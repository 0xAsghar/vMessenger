package ir.vmessenger.feature.map

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import ir.vmessenger.core.designsystem.foundation.PermissionStatus
import ir.vmessenger.core.designsystem.foundation.rememberRuntimePermission

/**
 * The location permission as state, re-read on every resume.
 *
 * Reading it once into a `remember` (what the old screen did) goes stale the moment the user
 * grants or revokes it in Settings, which is precisely the trip the "permanently denied" branch
 * sends them on. Fine or coarse is enough: someone who shares an approximate position still
 * shares one.
 */
@Composable
internal fun rememberLocationPermission(onChanged: (MapPermission) -> Unit): LocationPermissionController {
    val location = rememberRuntimePermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    val state = location.status.toMapPermission()
    val callback = rememberUpdatedState(onChanged)
    LaunchedEffect(state) { callback.value(state) }

    return remember(location) {
        LocationPermissionController(
            state = state,
            request = location::request,
            openSettings = location::openSettings,
        )
    }
}

/** The map does not tell "never asked" from "denied": either way the card offers to ask. */
private fun PermissionStatus.toMapPermission(): MapPermission = when (this) {
    PermissionStatus.Granted -> MapPermission.Granted
    PermissionStatus.PermanentlyDenied -> MapPermission.PermanentlyDenied
    PermissionStatus.NotAsked, PermissionStatus.Denied -> MapPermission.Denied
}
