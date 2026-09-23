package ir.vmessenger.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.Avatar
import ir.vmessenger.core.designsystem.component.SectionHeader
import ir.vmessenger.core.designsystem.component.VmButtonSize
import ir.vmessenger.core.designsystem.component.VmDivider
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmSwitch
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlin.math.roundToInt

private val SHEET_MAX_HEIGHT = 420.dp
private const val METERS_PER_KM = 1_000f

/** Past this a tenth of a kilometre is noise. */
private const val KM_WHOLE_FROM = 10f

/**
 * The sheet: who may see us, who we can see, and — when the permission is missing — what to do
 * about it. Peeking, it shows the sharing switch alone; expanded, the whole list.
 */
@Composable
internal fun ColumnScope.MapSheet(
    state: MapUiState,
    actions: MapActions,
    permission: LocationPermissionController,
    onPeekMeasured: (Int) -> Unit,
    onPickContacts: () -> Unit,
) {
    SharingRow(
        state = state,
        onToggle = actions.onToggleSharing,
        onPickContacts = onPickContacts,
        // Where the sharing row ends inside the sheet, handle included: the resting sheet shows
        // exactly that much, however large the text is set.
        modifier = Modifier
            .onGloballyPositioned { row ->
                onPeekMeasured((row.positionInParent().y + row.size.height).roundToInt())
            }
            // Measured with the row: the resting sheet ends in this space, not on the next edge.
            .padding(bottom = VmSpacing.md),
    )
    state.hint?.let { hint ->
        val isProblem = hint == MapHint.SelectContactFirst
        VmText(
            text = stringResource(
                if (isProblem) R.string.feature_map_select_contact_first else R.string.feature_map_request_sent,
            ),
            style = VmTheme.typography.bodySm,
            color = if (isProblem) VmTheme.colors.textCritical else VmTheme.colors.textSuccess,
            modifier = Modifier.padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xs),
        )
    }
    if (permission.state != MapPermission.Granted) {
        PermissionCard(permission = permission)
    }
    VmDivider(modifier = Modifier.padding(top = VmSpacing.sm))
    SectionHeader(title = stringResource(R.string.feature_map_contacts_title))
    WatcherList(state = state, onSelect = actions.onSelect, onRequestShare = actions.onRequestShare)
}

@Composable
private fun SharingRow(
    state: MapUiState,
    onToggle: () -> Unit,
    onPickContacts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            VmText(
                text = stringResource(R.string.feature_map_share_switch),
                style = VmTheme.typography.bodyLgMedium,
                color = VmTheme.colors.textPrimary,
            )
            val names = state.sharing.grantedNames
            VmText(
                text = if (names.isEmpty()) {
                    stringResource(R.string.feature_map_share_nobody)
                } else {
                    stringResource(
                        R.string.feature_map_share_targets,
                        VmTextFormat.list(names.map(VmTextFormat::isolate)),
                    )
                },
                style = VmTheme.typography.bodySm,
                color = VmTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VmTextButton(text = stringResource(R.string.feature_map_pick_contacts), onClick = onPickContacts)
        VmSwitch(checked = state.sharing.active, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun PermissionCard(permission: LocationPermissionController) {
    val permanentlyDenied = permission.state == MapPermission.PermanentlyDenied
    // The info fill, not the subtle one: on a sheet the subtle fill is the sheet's own colour in
    // the dark theme, and the card disappeared into it.
    VmSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm),
        shape = VmShapes.card,
        color = VmTheme.colors.bgInfoSubtle,
    ) {
        Column(
            modifier = Modifier.padding(VmSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            VmText(
                text = stringResource(
                    if (permanentlyDenied) {
                        R.string.feature_map_permission_denied_forever
                    } else {
                        R.string.feature_map_permission_rationale
                    },
                ),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textPrimary,
            )
            VmOutlinedButton(
                text = stringResource(
                    if (permanentlyDenied) {
                        R.string.feature_map_permission_settings
                    } else {
                        R.string.feature_map_permission_grant
                    },
                ),
                onClick = if (permanentlyDenied) permission.openSettings else permission.request,
                size = VmButtonSize.Medium,
            )
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
        VmText(
            text = stringResource(R.string.feature_map_contacts_empty),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textSecondary,
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
    VmSurface(
        color = if (selected) VmTheme.colors.bgAccentSubtle else Color.Transparent,
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
                VmText(
                    text = status.name,
                    style = VmTheme.typography.bodyLg,
                    color = VmTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                VmText(
                    text = marker?.lastUpdateLabel
                        ?: stringResource(R.string.feature_map_contact_not_sharing),
                    style = VmTheme.typography.bodySm,
                    color = VmTheme.colors.textSecondary,
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
        VmIcon(
            imageVector = Icons.Outlined.Visibility,
            contentDescription = stringResource(R.string.feature_map_contact_sees_me),
            tint = VmTheme.colors.iconSecondary,
            size = VmSizes.iconSm,
        )
    }
    // Only for a verified contact who is not already sharing: a request they are free to ignore.
    if (status.marker == null && status.verified) {
        VmIconButton(
            icon = Icons.Outlined.NotificationsActive,
            contentDescription = stringResource(R.string.feature_map_request_share),
            onClick = onRequestShare,
            tint = VmTheme.colors.iconAccent,
        )
    }
    status.marker?.distanceM?.let {
        VmText(
            text = distanceLabel(it),
            style = VmTheme.typography.bodySmMedium,
            color = VmTheme.colors.textAccent,
        )
    }
}

/** `۳۵۰ متر` below a kilometre, `۱٫۲ کیلومتر` above it, whole kilometres from ten on. */
@Composable
private fun distanceLabel(meters: Float): String = if (meters < METERS_PER_KM) {
    stringResource(R.string.feature_map_distance_m, VmTextFormat.digits(meters.toInt().toString()))
} else {
    val km = meters / METERS_PER_KM
    val shown = if (km >= KM_WHOLE_FROM) {
        VmTextFormat.digits(km.toInt().toString())
    } else {
        VmTextFormat.oneDecimal(km.toDouble())
    }
    stringResource(R.string.feature_map_distance_km, shown)
}
