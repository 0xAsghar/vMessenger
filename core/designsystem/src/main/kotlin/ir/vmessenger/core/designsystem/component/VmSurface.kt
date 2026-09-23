package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.foundation.ProvideVmContent
import ir.vmessenger.core.designsystem.foundation.VmIndication
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A piece of the screen with its own background: a card, a banner, a sheet, a bubble.
 *
 * Sets the content colour for everything inside, so text and icons placed on it read on it. The
 * clickable overload is the one to use for a pressable block — the press is drawn inside [shape],
 * in the content colour, so a pressed card stays a card.
 */
@Composable
@Suppress("LongParameterList") // A slot for each property of a surface; all optional but content.
fun VmSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = VmTheme.colors.bgCanvas,
    contentColor: Color = LocalVmContentColor.current,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    ProvideVmContent(color = contentColor) {
        Box(
            modifier = modifier.surface(shape, color, border, shadowElevation),
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

/** A [VmSurface] that responds to a tap. */
@Composable
@Suppress("LongParameterList") // A slot for each property of a surface; all optional but content.
fun VmSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = VmTheme.colors.bgCanvas,
    contentColor: Color = LocalVmContentColor.current,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    role: Role = Role.Button,
    content: @Composable () -> Unit,
) {
    ProvideVmContent(color = contentColor) {
        Box(
            modifier = modifier
                .surface(shape, color, border, shadowElevation)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = VmIndication,
                    enabled = enabled,
                    role = role,
                    onClick = onClick,
                ),
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}

private fun Modifier.surface(shape: Shape, color: Color, border: BorderStroke?, elevation: Dp): Modifier =
    this
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .background(color, shape)
        .clip(shape)
