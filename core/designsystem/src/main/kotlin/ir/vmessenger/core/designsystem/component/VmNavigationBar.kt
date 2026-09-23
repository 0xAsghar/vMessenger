package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.foundation.VmIndication
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The home shell's tab bar: flat, on the canvas, a hairline above it.
 *
 * No pill behind the selected tab — that capsule is Material 3's signature. The selected tab is
 * told apart by colour and weight alone: its icon and label in the text colour, the others in the
 * secondary grey.
 */
@Composable
fun VmNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    VmSurface(color = VmTheme.colors.bgCanvas, modifier = modifier.fillMaxWidth()) {
        Column {
            VmDivider()
            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    )
                    .height(BAR_HEIGHT),
                content = content,
            )
        }
    }
}

/** One tab within [VmNavigationBar]. */
@Composable
fun RowScope.VmNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = VmTheme.colors
    val tint = if (selected) c.textPrimary else c.textSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = VmIndication,
                role = Role.Tab,
                onClick = onClick,
            ),
    ) {
        VmIcon(imageVector = icon, contentDescription = null, tint = tint)
        VmText(
            text = label,
            style = if (selected) VmTheme.typography.bodySmMedium else VmTheme.typography.bodySm,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BAR_HEIGHT = 64.dp
