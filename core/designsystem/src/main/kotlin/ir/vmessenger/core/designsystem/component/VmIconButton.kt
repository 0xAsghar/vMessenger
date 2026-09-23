package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.foundation.ProvideVmContent
import ir.vmessenger.core.designsystem.foundation.VmIndication
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A tappable icon: 48dp to press, the icon itself 24dp, the press drawn as a circle around it.
 *
 * [contentDescription] names the action, and is required for the same reason as on [VmIcon]: an
 * icon button without one is a button a screen reader announces as "button" and nothing else.
 */
@Composable
@Suppress("LongParameterList") // One parameter per independent property of the button.
fun VmIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    touchSize: Dp = VmSizes.touchTarget,
) {
    val base = if (tint == Color.Unspecified) LocalVmContentColor.current else tint
    val color = if (enabled) base else VmTheme.colors.iconDisabled
    VmIconButtonFrame(onClick = onClick, enabled = enabled, modifier = modifier, touchSize = touchSize, color = color) {
        VmIcon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** [VmIconButton] with arbitrary content — an avatar, a badge over an icon. */
@Composable
@Suppress("LongParameterList") // The button's own knobs plus the content slot.
fun VmIconButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    touchSize: Dp = VmSizes.touchTarget,
    color: Color = LocalVmContentColor.current,
    content: @Composable () -> Unit,
) {
    ProvideVmContent(color = color) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(touchSize)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = VmIndication,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
        ) {
            content()
        }
    }
}
