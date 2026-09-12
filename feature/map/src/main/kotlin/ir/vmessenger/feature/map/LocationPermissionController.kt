package ir.vmessenger.feature.map

import androidx.compose.runtime.Stable

/** The permission plus the two things a screen can do about it. */
@Stable
internal class LocationPermissionController(
    val state: MapPermission,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)
