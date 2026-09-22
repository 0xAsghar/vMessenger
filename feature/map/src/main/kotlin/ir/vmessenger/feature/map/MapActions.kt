package ir.vmessenger.feature.map

import androidx.compose.runtime.Stable

/**
 * Every view-model call the map screen can make, bundled so no composable needs a long
 * parameter list. A data class so a `remember`ed instance compares by value.
 */
@Stable
data class MapActions(
    val onToggleSharing: () -> Unit,
    val onSetAccess: (String, Boolean) -> Unit,
    val onSelect: (String?) -> Unit,
    /** Ask a verified contact to share their location; a request, never a switch. */
    val onRequestShare: (String) -> Unit,
    val onFitAll: () -> Unit,
    val onFollowMe: () -> Unit,
    val onUserGesture: () -> Unit,
    val onStyleError: () -> Unit,
    val onRetryTiles: () -> Unit,
)
