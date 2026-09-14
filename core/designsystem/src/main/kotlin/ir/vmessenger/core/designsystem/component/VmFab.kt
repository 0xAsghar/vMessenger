package ir.vmessenger.core.designsystem.component

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ir.vmessenger.core.designsystem.theme.VmElevation

/**
 * The primary action of a screen. There is at most one, it is always primary-coloured, and it
 * always sits at [VmElevation.fab] — a FAB in a container colour reads as a secondary control,
 * which is the opposite of what it is.
 */
@Composable
fun VmFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = VmElevation.fab),
        modifier = modifier,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** [VmFab] with the action spelled out, for a screen whose primary action needs naming. */
@Composable
fun VmExtendedFab(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        text = { Text(text = text) },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = VmElevation.fab),
        modifier = modifier,
    )
}
