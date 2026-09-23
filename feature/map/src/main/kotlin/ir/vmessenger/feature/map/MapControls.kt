package ir.vmessenger.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmSmallFab
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/** Floating summary of what is being shared, in either direction. */
@Composable
internal fun SharingPill(sharing: SharingState, watcherCount: Int, modifier: Modifier = Modifier) {
    val label = when {
        sharing.active -> stringResource(
            R.string.feature_map_pill_sharing,
            VmTextFormat.digits(sharing.grantedNames.size.toString()),
        )
        watcherCount > 0 -> stringResource(
            R.string.feature_map_pill_watchers,
            VmTextFormat.digits(watcherCount.toString()),
        )
        else -> stringResource(R.string.feature_map_pill_off)
    }
    VmSurface(
        modifier = modifier,
        shape = VmShapes.pill,
        color = VmTheme.colors.bgElevated,
        contentColor = VmTheme.colors.textPrimary,
        shadowElevation = VmElevation.sheet,
    ) {
        VmText(
            text = label,
            style = VmTheme.typography.bodyMdMedium,
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
            VmSmallFab(
                icon = Icons.Outlined.CenterFocusStrong,
                contentDescription = stringResource(R.string.feature_map_action_fit_all),
                onClick = onFitAll,
            )
        }
        VmSmallFab(
            icon = Icons.Outlined.MyLocation,
            contentDescription = stringResource(R.string.feature_map_action_my_location),
            onClick = onFollowMe,
            tint = VmTheme.colors.iconAccent,
        )
    }
}

/** Shown when the basemap could not be fetched; the pins are still drawn on the fallback style. */
@Composable
internal fun TilesErrorBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    VmSurface(
        modifier = modifier.fillMaxWidth(),
        shape = VmShapes.field,
        color = VmTheme.colors.bgCriticalSubtle,
        contentColor = VmTheme.colors.textPrimary,
        shadowElevation = VmElevation.sheet,
    ) {
        Row(
            modifier = Modifier.padding(start = VmSpacing.md, top = VmSpacing.xs, bottom = VmSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
        ) {
            VmIcon(Icons.Outlined.CloudOff, contentDescription = null, tint = VmTheme.colors.iconCritical)
            VmText(
                text = stringResource(R.string.feature_map_tiles_error),
                style = VmTheme.typography.bodySm,
                modifier = Modifier.weight(1f),
            )
            VmTextButton(text = stringResource(R.string.feature_map_retry), onClick = onRetry)
        }
    }
}
