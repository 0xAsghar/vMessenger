package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * "Nothing here yet": the icon in a soft rounded tile, a title, a sentence under it and at most one
 * thing to do about it. The [icon] is decorative — the title and body carry the meaning — so it is
 * announced as nothing; the title is a heading.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: EmptyStateAction? = null,
) {
    val c = VmTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(VmSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VmSurface(shape = VmShapes.card, color = c.bgSubtle, contentColor = c.iconSecondary) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(TILE)) {
                VmIcon(imageVector = icon, contentDescription = null, size = GLYPH)
            }
        }
        Spacer(Modifier.height(VmSpacing.lg))
        VmText(
            text = title,
            style = VmTheme.typography.headingSm,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = TEXT_WIDTH)
                .semantics { heading() },
        )
        Spacer(Modifier.height(VmSpacing.sm))
        VmText(
            text = body,
            style = VmTheme.typography.bodyMd,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TEXT_WIDTH),
        )
        if (action != null) {
            Spacer(Modifier.height(VmSpacing.xl))
            VmButton(text = action.label, onClick = action.onClick, size = VmButtonSize.Medium)
        }
    }
}

private val TILE = 64.dp
private val GLYPH = 32.dp

/** A sentence wider than this is hard to take in at a glance, which is all an empty state gets. */
private val TEXT_WIDTH = 360.dp
