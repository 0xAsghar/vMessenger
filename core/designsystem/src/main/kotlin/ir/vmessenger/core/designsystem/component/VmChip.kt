package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * One option in a small set the user picks between: a pill, filled in the action colour when
 * picked, outlined when not — the way Element X marks its filters.
 */
@Composable
fun VmChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val c = VmTheme.colors
    VmSurface(
        onClick = onClick,
        shape = VmShapes.pill,
        color = if (selected) c.bgActionPrimary else Color.Transparent,
        contentColor = if (selected) c.textOnActionPrimary else c.textPrimary,
        border = if (selected) null else BorderStroke(1.dp, c.borderInteractive),
        // One of a set, exactly one picked: announced as a radio button, with its state.
        role = Role.RadioButton,
        modifier = modifier
            .defaultMinSize(minHeight = CHIP_HEIGHT)
            .semantics { this.selected = selected },
    ) {
        // Centred: a chip given a share of a row's width is wider than its label.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            if (leadingIcon != null) {
                VmIcon(imageVector = leadingIcon, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(6.dp))
            }
            VmText(
                text = label,
                style = VmTheme.typography.bodyMdMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CHIP_HEIGHT = 36.dp
