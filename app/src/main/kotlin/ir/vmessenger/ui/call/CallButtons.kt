package ir.vmessenger.ui.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

private val PrimaryButtonSize = 72.dp
private val ToggleButtonSize = 60.dp

/** A filled disc with its label underneath; the label is also its accessible name. */
@Composable
internal fun RoundCallButton(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VmSurface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier
                .size(PrimaryButtonSize)
                .semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                VmIcon(imageVector = icon, contentDescription = null, tint = content)
            }
        }
        ButtonLabel(label)
    }
}

/**
 * Mute and speaker: a disc that fills in while it is on. A switch to assistive technology, which
 * announces the state — the icon alone would not say whether the microphone is live.
 */
@Composable
internal fun ToggleCallButton(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = VmTheme.colors
    val container = if (checked) colors.bgActionPrimary else colors.bgSubtleStrong
    val content = if (checked) colors.textOnActionPrimary else colors.iconPrimary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VmSurface(
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(ToggleButtonSize),
        ) {
            // Inside the disc, so the press ripple is clipped round with it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                VmIcon(imageVector = icon, contentDescription = null, tint = content)
            }
        }
        ButtonLabel(label)
    }
}

/** Seen, not read out: the button above already carries these words as its name. */
@Composable
private fun ButtonLabel(text: String) {
    VmText(
        text = text,
        style = VmTheme.typography.bodySmMedium,
        color = VmTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = VmSpacing.sm)
            .clearAndSetSemantics { },
    )
}
