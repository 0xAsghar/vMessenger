package ir.vmessenger.feature.settings

import androidx.annotation.StringRes
import ir.vmessenger.domain.model.ActivityEventKind

/**
 * A sentence for each kind of logged event.
 *
 * Exhaustive with no `else`: a new [ActivityEventKind] must be given words before it can be recorded,
 * rather than appearing in the user's own log as an enum name they have to decode.
 */
@StringRes
@Suppress("CyclomaticComplexMethod") // A lookup table; splitting it would hide which kinds exist.
internal fun ActivityEventKind.labelRes(): Int = when (this) {
    ActivityEventKind.IdentityCreated -> R.string.feature_settings_activity_identity_created
    ActivityEventKind.AppUnlocked -> R.string.feature_settings_activity_unlocked
    ActivityEventKind.AppLocked -> R.string.feature_settings_activity_locked
    ActivityEventKind.NodeAdded -> R.string.feature_settings_activity_node_added
    ActivityEventKind.NodeRemoved -> R.string.feature_settings_activity_node_removed
    ActivityEventKind.NodeProvisioned -> R.string.feature_settings_activity_node_provisioned
    ActivityEventKind.NodeUpdated -> R.string.feature_settings_activity_node_updated
    ActivityEventKind.NetworkConnected -> R.string.feature_settings_activity_network_connected
    ActivityEventKind.NetworkDisconnected -> R.string.feature_settings_activity_network_disconnected
    ActivityEventKind.PermissionGranted -> R.string.feature_settings_activity_permission_granted
    ActivityEventKind.PermissionDenied -> R.string.feature_settings_activity_permission_denied
    ActivityEventKind.LocationSharingStarted -> R.string.feature_settings_activity_location_started
    ActivityEventKind.LocationSharingStopped -> R.string.feature_settings_activity_location_stopped
    ActivityEventKind.CallPlaced -> R.string.feature_settings_activity_call_placed
    ActivityEventKind.CallReceived -> R.string.feature_settings_activity_call_received
    ActivityEventKind.CallEnded -> R.string.feature_settings_activity_call_ended
    ActivityEventKind.ContactAdded -> R.string.feature_settings_activity_contact_added
    ActivityEventKind.ContactBlocked -> R.string.feature_settings_activity_contact_blocked
    ActivityEventKind.AccountWiped -> R.string.feature_settings_activity_account_wiped
    ActivityEventKind.Failure -> R.string.feature_settings_activity_failure
}
