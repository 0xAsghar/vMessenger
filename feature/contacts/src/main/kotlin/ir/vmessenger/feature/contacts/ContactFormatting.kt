package ir.vmessenger.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.vm
import ir.vmessenger.domain.model.ContactRelationshipStatus
import java.util.Locale

private const val METERS_PER_KM = 1000.0
private val BadgeIconSize = 16.dp

/** "۳۴۰ متر" / "۱٫۲ کیلومتر" — Persian digits, matching the rest of the UI. */
@Composable
internal fun distanceLabel(meters: Double): String = if (meters >= METERS_PER_KM) {
    stringResource(R.string.contacts_distance_km, persianDecimal(meters / METERS_PER_KM))
} else {
    stringResource(R.string.contacts_distance_meters, VmTextFormat.persianDigits(meters.toInt().toString()))
}

@Composable
internal fun statusLabel(status: ContactRelationshipStatus): String = when (status) {
    ContactRelationshipStatus.PENDING_OUT -> stringResource(R.string.contacts_status_pending_out)
    ContactRelationshipStatus.PENDING_IN -> stringResource(R.string.contacts_status_pending_in)
    ContactRelationshipStatus.REJECTED -> stringResource(R.string.contacts_status_rejected)
    ContactRelationshipStatus.APPROVED -> stringResource(R.string.contacts_status_approved)
}

/** Small outlined pill saying where the relationship stands; approved contacts show none. */
@Composable
internal fun ContactStatusChip(
    status: ContactRelationshipStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
        )
    }
}

/** The contact is sharing their live location with us; the distance is shown when we have a fix. */
@Composable
internal fun DistanceBadge(
    distanceMeters: Double?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.xxs),
    ) {
        Icon(
            imageVector = Icons.Outlined.LocationOn,
            contentDescription = stringResource(R.string.contacts_location_shared),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(BadgeIconSize),
        )
        if (distanceMeters != null) {
            Text(
                text = distanceLabel(distanceMeters),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Shield shown while the contact's identity key changed and the user has not accepted it. */
@Composable
internal fun KeyChangeShield(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Outlined.GppMaybe,
        contentDescription = stringResource(R.string.contacts_key_change_pending),
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier.size(BadgeIconSize),
    )
}

/** Blocked contacts keep their row on the blocked-contacts screen; this marks them there. */
@Composable
internal fun BlockedChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.vm.keyChangeWarning,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.contacts_status_blocked),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
        )
    }
}

private fun persianDecimal(value: Double): String =
    VmTextFormat.persianDigits(String.format(Locale.US, "%.1f", value)).replace('.', '٫')
