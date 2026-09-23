package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTextStyles
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The chrome around one message: side, shape, colours and the 78% width cap. The payload is a
 * `*BubbleContent` composable, and the trailing line is [BubbleMeta].
 */
@Composable
@Suppress("LongParameterList") // Compose slot API: shape, colours and sizing are each optional.
fun MessageBubble(
    direction: BubbleDirection,
    modifier: Modifier = Modifier,
    shape: Shape = if (direction == BubbleDirection.Outgoing) VmShapes.bubbleOutgoing else VmShapes.bubbleIncoming,
    colors: MessageBubbleColors = MessageBubbleDefaults.colors(direction),
    matchWidestChild: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val alignment = if (direction == BubbleDirection.Outgoing) Alignment.CenterEnd else Alignment.CenterStart
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.sm, vertical = VmSpacing.xxs),
        contentAlignment = alignment,
    ) {
        VmSurface(
            shape = shape,
            color = colors.container,
            contentColor = colors.content,
            modifier = Modifier.widthIn(max = maxWidth * VmSizes.bubbleMaxWidthFraction),
        ) {
            Column(
                // Opt-in, because intrinsic measurement asks every child for a width it can
                // report — and a Canvas cannot. A waveform or a still-loading image reports zero,
                // which collapsed voice and media bubbles to a fraction of their size. It is only
                // needed so a reply quote can fill the bubble instead of shrinking to its own
                // ellipsised text, so only a bubble that has one asks for it.
                modifier = Modifier
                    .then(if (matchWidestChild) Modifier.width(IntrinsicSize.Max) else Modifier)
                    .padding(VmSpacing.sm),
                content = content,
            )
        }
    }
}

/** Trailing `۱۴:۰۵ ✓✓` line of a bubble. */
@Composable
fun BubbleMeta(
    time: String,
    modifier: Modifier = Modifier,
    ticks: DeliveryTicksState? = null,
    edited: Boolean = false,
    expiring: Boolean = false,
) {
    val quiet = VmTheme.colors.textSecondary
    Row(
        modifier = modifier.padding(top = VmSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs),
    ) {
        if (expiring) {
            VmIcon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = stringResource(R.string.vm_bubble_timed),
                tint = quiet,
                size = VmSizes.iconSm,
            )
        }
        if (edited) {
            VmText(
                text = stringResource(R.string.vm_bubble_edited),
                style = VmTextStyles.bubbleTime,
                color = quiet,
            )
        }
        VmText(
            text = time,
            style = VmTextStyles.bubbleTime,
            color = quiet,
        )
        if (ticks != null) {
            DeliveryTicks(state = ticks)
        }
    }
}
