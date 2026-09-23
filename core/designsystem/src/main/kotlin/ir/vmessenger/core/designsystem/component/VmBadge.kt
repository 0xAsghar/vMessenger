package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A small pill with a count or a word in it: unread messages, "new version".
 *
 * [emphasized] is the accent, for something that wants attention; otherwise it is grey — the
 * unread count of a muted conversation, which is information but not a call.
 */
@Composable
fun VmBadge(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
) {
    val c = VmTheme.colors
    VmSurface(
        shape = VmShapes.pill,
        color = if (emphasized) c.bgAccent else c.bgSubtleStrong,
        contentColor = if (emphasized) c.textOnSolid else c.textSecondary,
        modifier = modifier,
    ) {
        VmText(
            text = text,
            style = VmTheme.typography.bodyXsMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .defaultMinSize(minWidth = MIN_WIDTH)
                .padding(horizontal = VmSpacing.sm - VmSpacing.xxs, vertical = VmSpacing.xxs),
        )
    }
}

/** A lone badge still reads as a pill rather than a speck, even around a single digit. */
private val MIN_WIDTH = 12.dp
