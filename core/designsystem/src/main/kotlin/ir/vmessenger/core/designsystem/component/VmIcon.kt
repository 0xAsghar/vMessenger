package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor

/** The standard icon size: 24dp, the grid every icon in the app is drawn to. */
val VmIconSize: Dp = 24.dp

/**
 * An icon, tinted with the surrounding content colour unless told otherwise.
 *
 * [contentDescription] is required, not defaulted: an icon that means something must say what, and
 * one that is decoration next to a label must say so explicitly with `null`. The defaulted version
 * of this parameter is how a button ends up with no accessible name.
 */
@Composable
fun VmIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalVmContentColor.current,
    size: Dp = VmIconSize,
) {
    VmIcon(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
        size = size,
    )
}

/** [VmIcon] for a painter; [Color.Unspecified] as [tint] draws it in its own colours. */
@Composable
fun VmIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalVmContentColor.current,
    size: Dp = VmIconSize,
) {
    val filter = remember(tint) { if (tint == Color.Unspecified) null else ColorFilter.tint(tint) }
    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    androidx.compose.foundation.layout.Box(
        modifier
            .size(size)
            .paint(painter, colorFilter = filter, contentScale = ContentScale.Fit)
            .then(described),
    )
}
