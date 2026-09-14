package ir.vmessenger.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmSpacing

/** Floating summary of what is being shared, in either direction. */
@Composable
internal fun SharingPill(sharing: SharingState, watcherCount: Int, modifier: Modifier = Modifier) {
    val label = when {
        sharing.active -> stringResource(
            R.string.feature_map_pill_sharing,
            VmTextFormat.persianDigits(sharing.grantedNames.size.toString()),
        )
        watcherCount > 0 -> stringResource(
            R.string.feature_map_pill_watchers,
            VmTextFormat.persianDigits(watcherCount.toString()),
        )
        else -> stringResource(R.string.feature_map_pill_off)
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = VmElevation.sheet,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = VmSpacing.md, vertical = VmSpacing.sm),
        )
    }
}

/**
 * Follow-me and fit-all; the only two camera affordances the screen offers.
 *
 * [onFitAll] is null when nobody is sharing. The button used to stay and quietly fall back to
 * follow-me, which is why it read as a broken duplicate of the control beside it.
 */
@Composable
internal fun MapCameraButtons(onFollowMe: () -> Unit, onFitAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onFitAll != null) {
            SmallFloatingActionButton(
                onClick = onFitAll,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    Icons.Outlined.CenterFocusStrong,
                    contentDescription = stringResource(R.string.feature_map_action_fit_all),
                )
            }
        }
        SmallFloatingActionButton(
            onClick = onFollowMe,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                Icons.Outlined.MyLocation,
                contentDescription = stringResource(R.string.feature_map_action_my_location),
            )
        }
    }
}

/** Shown when the basemap could not be fetched; the pins are still drawn on the fallback style. */
@Composable
internal fun TilesErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = VmElevation.sheet,
    ) {
        Row(
            modifier = Modifier.padding(start = VmSpacing.md, top = VmSpacing.xs, bottom = VmSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text(
                text = stringResource(R.string.feature_map_tiles_error),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.feature_map_retry)) }
        }
    }
}
