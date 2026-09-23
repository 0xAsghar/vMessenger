package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A screen's primary action, floating: a circle in the action colour with a soft shadow. At most
 * one per screen.
 */
@Composable
fun VmFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VmSurface(
        onClick = onClick,
        shape = CircleShape,
        color = VmTheme.colors.bgActionPrimary,
        contentColor = VmTheme.colors.textOnActionPrimary,
        shadowElevation = VmElevation.fab,
        modifier = modifier
            .size(FAB_SIZE)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(ICON_INSET)) {
            VmIcon(imageVector = icon, contentDescription = null)
        }
    }
}

/**
 * A small round control floating over content that is not the app's own — a map's camera buttons:
 * the canvas colour with a shadow, so it stands off whatever is drawn behind it.
 */
@Composable
fun VmSmallFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = VmTheme.colors.iconPrimary,
) {
    VmSurface(
        onClick = onClick,
        shape = CircleShape,
        color = VmTheme.colors.bgElevated,
        contentColor = tint,
        shadowElevation = VmElevation.sheet,
        modifier = modifier
            .size(SMALL_FAB_SIZE)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            VmIcon(imageVector = icon, contentDescription = null)
        }
    }
}

/**
 * [VmFab] with the action spelled out, for a screen whose primary action needs naming.
 *
 * The label is real text inside the pressable surface, so it *is* the button's accessible name.
 * The Material version this replaced cleared its label's semantics and expected the icon to carry
 * the name; given a decorative icon, the contacts screen's main action had no name at all.
 */
@Composable
fun VmExtendedFab(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VmSurface(
        onClick = onClick,
        shape = VmShapes.pill,
        color = VmTheme.colors.bgActionPrimary,
        contentColor = VmTheme.colors.textOnActionPrimary,
        shadowElevation = VmElevation.fab,
        modifier = modifier.height(FAB_SIZE),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 20.dp, end = 24.dp),
        ) {
            VmIcon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            VmText(text = text, style = VmTheme.typography.bodyLgMedium)
        }
    }
}

private val FAB_SIZE = 56.dp
private val ICON_INSET = 16.dp
private val SMALL_FAB_SIZE = 44.dp
