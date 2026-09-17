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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.format.VmDateFormat
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

/** Between the distance and the last-heard time on one line; reads the same in either direction. */
private const val SUBTITLE_SEPARATOR = " · "

/**
 * The text of a contact row's second line: how far away they are, when they share their position,
 * and when we last heard from them — both, not one in place of the other.
 *
 * It used to be the user hash, which is unreadable, identical in shape for everyone, and already
 * available on the detail screen and its share row. Then it became a priority chain in which a
 * distance *replaced* the last-heard time, so a sharing contact lost the one thing that says whether
 * that distance is current.
 *
 * "Last heard from", not "last seen" — the underlying column is touched by anything the contact
 * addresses to us, receipts and control packets included, so calling it presence would overstate
 * it. It is null for a contact who has never sent anything and for every contact after a backup
 * restore, which is why the pending case has its own words rather than an empty line.
 */
@Composable
internal fun contactSubtitle(contact: ContactRow): String? {
    val distance = contact.distanceMeters?.takeIf { contact.sharesLocation }?.let { distanceLabel(it) }
    val heard = when {
        contact.lastSeenUnixMs != null -> stringResource(
            R.string.contacts_last_heard,
            VmDateFormat.relative(contact.lastSeenUnixMs),
        )
        contact.status == ContactRelationshipStatus.PENDING_OUT -> stringResource(R.string.contacts_never_heard)
        else -> null
    }
    return listOfNotNull(distance, heard).joinToString(SUBTITLE_SEPARATOR).ifEmpty { null }
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

/**
 * A contact row's second line, [text] from [contactSubtitle], led by a pin while the contact shares
 * their live location with us.
 *
 * The distance used to be drawn twice — here, and again as a badge at the far end of the row — so
 * the pin now lives on this line only, next to the number it explains.
 */
@Composable
internal fun ContactSubtitle(
    text: String?,
    sharesLocation: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.xxs),
    ) {
        if (sharesLocation) {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = stringResource(R.string.contacts_location_shared),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(BadgeIconSize),
            )
        }
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
