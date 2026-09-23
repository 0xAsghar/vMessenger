package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.foundation.ProvideVmContent
import ir.vmessenger.core.designsystem.theme.VmMotion
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The bar across the top of a screen: back, a title, actions. Flat, on the canvas colour.
 *
 * It does not change colour when the content scrolls under it — a hairline appears instead
 * ([showDivider]), which is how Element X separates a bar from scrolled content without making the
 * bar itself louder. It pads itself for the status bar, so it can sit at the very top of an
 * edge-to-edge window.
 */
@Composable
fun VmTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    showDivider: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        ProvideVmContent(color = VmTheme.colors.iconPrimary) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .padding(start = if (navigationIcon != null) EDGE_WITH_ICON else EDGE, end = EDGE_WITH_ICON),
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                    Box(Modifier.padding(start = TITLE_GAP))
                }
                Box(Modifier.weight(1f)) {
                    ProvideVmContent(color = VmTheme.colors.textPrimary, style = VmTheme.typography.headingSm) {
                        title()
                    }
                }
                actions()
            }
        }
        AnimatedVisibility(visible = showDivider, enter = fadeIn(VmMotion.fade()), exit = fadeOut(VmMotion.fade())) {
            VmDivider()
        }
    }
}

/** A title and an optional second line under it, both kept to one line. */
@Composable
fun VmTopBarTitle(title: String, subtitle: String? = null) {
    Column {
        VmText(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            VmText(
                text = subtitle,
                style = VmTheme.typography.bodySm,
                color = VmTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The back arrow, mirrored for right-to-left, named for a screen reader. */
@Composable
fun VmBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    VmIconButton(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = stringResource(R.string.nav_back),
        onClick = onClick,
        modifier = modifier,
    )
}

private val BAR_HEIGHT = 64.dp
private val EDGE = 16.dp
private val EDGE_WITH_ICON = 4.dp
private val TITLE_GAP = 4.dp
