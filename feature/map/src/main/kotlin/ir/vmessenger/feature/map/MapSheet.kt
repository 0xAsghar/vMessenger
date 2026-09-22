package ir.vmessenger.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

private val SHEET_MAX_HEIGHT = 420.dp
private const val METERS_PER_KM = 1_000f

/**
 * The sheet: who may see us, who we can see, and — when the permission is missing — what to do
 * about it. Peeking, it shows the sharing switch alone; expanded, the whole list.
 */
@Composable
internal fun ColumnScope.MapSheet(
    state: MapUiState,
    actions: MapActions,
    permission: LocationPermissionController,
    onPickContacts: () -> Unit,
) {
    SharingRow(state = state, onToggle = actions.onToggleSharing, onPickContacts = onPickContacts)
    state.hint?.let { hint ->
        val isProblem = hint == MapHint.SelectContactFirst
        Text(
            text = stringResource(
                if (isProblem) R.string.feature_map_select_contact_first else R.string.feature_map_request_sent,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xs),
        )
    }
    if (permission.state != MapPermission.Granted) {
        PermissionCard(permission = permission)
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = VmSpacing.sm))
    SectionHeader(title = stringResource(R.string.feature_map_contacts_title))
    WatcherList(state = state, onSelect = actions.onSelect, onRequestShare = actions.onRequestShare)
}

@Composable
private fun SharingRow(state: MapUiState, onToggle: () -> Unit, onPickContacts: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.feature_map_share_switch),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = state.sharing.grantedNames.takeIf { it.isNotEmpty() }
                    ?.let { stringResource(R.string.feature_map_share_targets, it.joinToString("، ")) }
                    ?: stringResource(R.string.feature_map_share_nobody),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onPickContacts) { Text(stringResource(R.string.feature_map_pick_contacts)) }
        Switch(checked = state.sharing.active, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun PermissionCard(permission: LocationPermissionController) {
    val permanentlyDenied = permission.state == MapPermission.PermanentlyDenied
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(VmSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.xs),
        ) {
            Text(
                text = stringResource(
                    if (permanentlyDenied) {
                        R.string.feature_map_permission_denied_forever
                    } else {
                        R.string.feature_map_permission_rationale
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = if (permanentlyDenied) permission.openSettings else permission.request,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(
                    stringResource(
                        if (permanentlyDenied) {
                            R.string.feature_map_permission_settings
                        } else {
                            R.string.feature_map_permission_grant
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun WatcherList(
    state: MapUiState,
    onSelect: (String?) -> Unit,
    onRequestShare: (String) -> Unit,
) {
    if (state.contactStatus.isEmpty()) {
        Text(
            text = stringResource(R.string.feature_map_contacts_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
        )
        return
    }
    LazyColumn(modifier = Modifier.heightIn(max = SHEET_MAX_HEIGHT)) {
        items(state.contactStatus, key = { it.contactId }) { status ->
            WatcherRow(
                status = status,
                selected = status.contactId == state.selectedContactId,
                onClick = { onSelect(status.contactId) },
                onRequestShare = { onRequestShare(status.contactId) },
            )
        }
    }
}

@Composable
private fun WatcherRow(
    status: ContactLocationStatus,
    selected: Boolean,
    onClick: () -> Unit,
    onRequestShare: () -> Unit,
) {
    val marker = status.marker
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        color = background,
        onClick = onClick,
        // There is nothing to centre the map on for a contact who is not sharing.
        enabled = marker != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            val seed = remember(status.seedHex) { status.seedHex.toSeedBytes() }
            Avatar(seed = seed, name = status.name, size = VmSizes.avatarSm)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = marker?.lastUpdateLabel
                        ?: stringResource(R.string.feature_map_contact_not_sharing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowTrailing(status = status, onRequestShare = onRequestShare)
        }
    }
}

/** The other direction and the distance: whether they can see us, and how far away they are. */
@Composable
private fun RowTrailing(status: ContactLocationStatus, onRequestShare: () -> Unit) {
    if (status.granted) {
        Icon(
            imageVector = Icons.Outlined.Visibility,
            contentDescription = stringResource(R.string.feature_map_contact_sees_me),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(VmSizes.iconSm),
        )
    }
    // Only for a verified contact who is not already sharing: a request they are free to ignore.
    if (status.marker == null && status.verified) {
        IconButton(onClick = onRequestShare) {
            Icon(
                imageVector = Icons.Outlined.NotificationsActive,
                contentDescription = stringResource(R.string.feature_map_request_share),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    status.marker?.distanceM?.let {
        Text(
            text = distanceLabel(it),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** `۳۵۰ متر` below a kilometre, `۱٫۲ کیلومتر` above it. */
@Composable
private fun distanceLabel(meters: Float): String = if (meters < METERS_PER_KM) {
    stringResource(R.string.feature_map_distance_m, VmTextFormat.persianDigits(meters.toInt().toString()))
} else {
    val km = meters / METERS_PER_KM
    val rounded = if (km >= 10f) km.toInt().toString() else ((km * 10).toInt() / 10f).toString()
    stringResource(R.string.feature_map_distance_km, VmTextFormat.persianDigits(rounded).replace('.', '٫'))
}
