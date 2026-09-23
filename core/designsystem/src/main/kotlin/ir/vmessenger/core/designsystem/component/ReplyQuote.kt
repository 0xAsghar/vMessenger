package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

private val QuoteBarWidth = 3.dp
private val QuoteMinHeight = 36.dp

/** A wash of whatever colour the quote sits on, so it reads as set-in on a bubble of either side. */
private const val QUOTE_ALPHA = 0.07f
private val QuoteShape = RoundedCornerShape(6.dp)

/**
 * The quoted message shown above a reply, both inside a bubble and in the composer strip.
 *
 * [QuoteMinHeight] is a minimum, not a fixed height — it was applied with `height()` despite the
 * name, and the two Persian lines inside need more than it allows, so the descenders of the second
 * line were sheared off.
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
            // The accent bar fills the row's height, and a wrap-content row would hand it the
            // *incoming* maximum instead — in the composer's reply strip that is the whole screen,
            // which made the bar, the strip and the bottom bar screen-tall. Intrinsic-min resolves
            // the row to its tallest real child first, so there is something finite to fill.
            .height(IntrinsicSize.Min)
            .clip(QuoteShape)
            .background(LocalVmContentColor.current.copy(alpha = QUOTE_ALPHA)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(QuoteBarWidth)
                .background(VmTheme.colors.iconAccent),
        )
        Column(modifier = Modifier.padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xs)) {
            VmText(
                text = senderName,
                style = VmTheme.typography.bodySmMedium,
                color = VmTheme.colors.textAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VmText(
                text = preview,
                style = VmTheme.typography.bodySm,
                color = VmTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
