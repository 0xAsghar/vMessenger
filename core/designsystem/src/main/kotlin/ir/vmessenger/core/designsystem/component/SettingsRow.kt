package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing

private const val DISABLED_ALPHA = 0.38f

/**
 * One settings entry. [trailing] decides what sits at the end of the row, and a
 * [SettingsTrailing.Switch] also makes the whole row toggle when it is tapped.
 */
@Suppress("LongParameterList") // Compose slot API: icon, two labels, trailing, enabled, click.
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    trailing: SettingsTrailing = SettingsTrailing.Chevron,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val rowClick = rowClickHandler(trailing, onClick)
    val clickable = if (enabled && rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = VmSizes.touchTarget)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LocalContentColor.current.copy(alpha = contentAlpha),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalContentColor.current.copy(alpha = contentAlpha),
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
        TrailingContent(trailing = trailing, enabled = enabled)
    }
}

private fun rowClickHandler(
    trailing: SettingsTrailing,
    onClick: (() -> Unit)?,
): (() -> Unit)? = if (trailing is SettingsTrailing.Switch) {
    { trailing.onCheckedChange(!trailing.checked) }
} else {
    onClick
}

@Composable
private fun TrailingContent(trailing: SettingsTrailing, enabled: Boolean) {
    when (trailing) {
        is SettingsTrailing.None -> Unit
        is SettingsTrailing.Chevron -> Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.vm_settings_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SettingsTrailing.Switch -> Switch(
            checked = trailing.checked,
            onCheckedChange = trailing.onCheckedChange,
            enabled = enabled,
        )
        is SettingsTrailing.Badge -> Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text(
                text = trailing.text,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
            )
        }
        is SettingsTrailing.Text -> Text(
            text = trailing.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
