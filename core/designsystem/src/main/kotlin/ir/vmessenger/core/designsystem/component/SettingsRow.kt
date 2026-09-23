package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * One settings entry — and one action in a sheet: an optional icon, a label with an optional line
 * under it, and whatever [trailing] puts at the end. [destructive] turns the label and icon the
 * warning colour, for the entries that delete, block or leave.
 *
 * A [SettingsTrailing.Switch] row is one toggle, not a row with a switch in it: the whole row flips
 * it and a screen reader hears a single "switch, on" rather than a row and then a switch.
 */
@Suppress("LongParameterList") // Compose slot API: icon, two labels, trailing, state flags, click.
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    trailing: SettingsTrailing = SettingsTrailing.Chevron,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = VmTheme.colors
    val labelColor = when {
        !enabled -> c.textDisabled
        destructive -> c.textCritical
        else -> c.textPrimary
    }
    val iconColor = when {
        !enabled -> c.iconDisabled
        destructive -> c.iconCritical
        else -> c.iconSecondary
    }
    val interaction = when {
        trailing is SettingsTrailing.Switch -> Modifier.toggleable(
            value = trailing.checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = trailing.onCheckedChange,
        )
        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interaction)
            .heightIn(min = MIN_HEIGHT)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.lg),
    ) {
        if (icon != null) {
            VmIcon(imageVector = icon, contentDescription = null, tint = iconColor)
        }
        Column(modifier = Modifier.weight(1f)) {
            VmText(text = label, style = VmTheme.typography.bodyLg, color = labelColor)
            if (supporting != null) {
                VmText(
                    text = supporting,
                    style = VmTheme.typography.bodyMd,
                    color = if (enabled) c.textSecondary else c.textDisabled,
                )
            }
        }
        TrailingContent(trailing = trailing, enabled = enabled)
    }
}

@Composable
private fun TrailingContent(trailing: SettingsTrailing, enabled: Boolean) {
    val c = VmTheme.colors
    when (trailing) {
        is SettingsTrailing.None -> Unit
        is SettingsTrailing.Chevron -> VmIcon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = stringResource(R.string.vm_settings_open),
            tint = if (enabled) c.iconTertiary else c.iconDisabled,
        )
        // The row is the control; the switch only shows its state.
        is SettingsTrailing.Switch -> VmSwitch(checked = trailing.checked, onCheckedChange = null, enabled = enabled)
        is SettingsTrailing.Badge -> VmBadge(text = trailing.text)
        is SettingsTrailing.Text -> VmText(
            text = trailing.value,
            style = VmTheme.typography.bodyMd,
            color = if (enabled) c.textSecondary else c.textDisabled,
        )
    }
}

/** One line of label and an icon sit comfortably in this; two lines grow the row past it. */
private val MIN_HEIGHT = 56.dp
