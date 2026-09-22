package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * The design system's buttons. Every screen goes through these rather than the Material primitive, so
 * a change to shape, colour or the icon-to-label gap lands in one place. [VmButton] is the primary
 * action, [VmOutlinedButton] its secondary, and [VmTextButton] the lowest-emphasis, dismissive one.
 */
@Composable
fun VmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        VmButtonLabel(text = text, leadingIcon = leadingIcon)
    }
}

/** A lower-emphasis action that still needs an outline: the secondary of a pair. */
@Composable
fun VmOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        VmButtonLabel(text = text, leadingIcon = leadingIcon)
    }
}

/** The lowest-emphasis action: no container, for a tertiary or dismissive choice. */
@Composable
fun VmTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text = text)
    }
}

@Composable
private fun VmButtonLabel(text: String, leadingIcon: ImageVector?) {
    if (leadingIcon != null) {
        Icon(imageVector = leadingIcon, contentDescription = null, modifier = Modifier.size(VmSizes.iconMd))
        Spacer(modifier = Modifier.width(VmSpacing.sm))
    }
    Text(text = text)
}
