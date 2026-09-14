package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmSpacing

/** A hairline: the section outline separates, it does not decorate. */
private val SectionBorderWidth = 1.dp

/** Outline at full strength would out-weigh the rows it encloses. */
private const val BORDER_ALPHA = 0.35f

/** The divider sits between two rows of the same card, so it is fainter still than the border. */
private const val DIVIDER_ALPHA = 0.25f

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = VmSpacing.xs, bottom = VmSpacing.sm),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = VmElevation.none,
            shadowElevation = VmElevation.none,
            border = BorderStroke(
                width = SectionBorderWidth,
                color = MaterialTheme.colorScheme.outline.copy(alpha = BORDER_ALPHA),
            ),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = VmSpacing.lg),
        color = MaterialTheme.colorScheme.outline.copy(alpha = DIVIDER_ALPHA),
    )
}
