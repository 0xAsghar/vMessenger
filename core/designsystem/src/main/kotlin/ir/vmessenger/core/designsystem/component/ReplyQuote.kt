package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmSpacing

private val QuoteBarWidth = 3.dp
private val QuoteMinHeight = 36.dp
private const val QUOTE_ALPHA = 0.12f

/**
 * The quoted message shown above a reply, both inside a bubble and in the composer strip.
 *
 * [QuoteMinHeight] is a minimum, not a fixed height — it was applied with `height()` despite the
 * name, and the two Persian lines inside need more than it allows, so the descenders of the second
 * line were sheared off. The accent bar still spans the full height because it fills, not measures.
 */
@Composable
fun ReplyQuote(
    senderName: String,
    preview: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .then(clickable)
            .heightIn(min = QuoteMinHeight)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = QUOTE_ALPHA)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(QuoteBarWidth)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xs)) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
