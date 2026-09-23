package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * A line the conversation says about itself — «X» گروه را ترک کرد — centred in small secondary
 * text, owned by nobody, so it cannot be mistaken for a message.
 *
 * The lines are Persian by construction, so the paragraph direction is pinned: content direction
 * would let a Latin name at the start of «Ali به گروه اضافه شد» flip the whole sentence. Newly
 * written lines isolate the name themselves; pinning here covers the ones already stored.
 */
@Composable
fun SystemMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        VmText(
            text = text,
            style = VmTheme.typography.bodySm.copy(textDirection = TextDirection.Rtl),
            color = VmTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(VmSizes.bubbleMaxWidthFraction)
                .padding(vertical = VmSpacing.xs),
        )
    }
}
