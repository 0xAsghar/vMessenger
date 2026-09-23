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
 * A line the conversation says about itself — «X» گروه را ترک کرد, "X left the group" — centred
 * in small secondary text, owned by nobody, so it cannot be mistaken for a message.
 *
 * The paragraph takes its direction from the line itself: a line keeps the language it was written
 * in, so a history can hold both. The names inside are isolated when the line is written, and an
 * isolated name is skipped when the direction is decided — a Latin name leading a Persian line no
 * longer turns it left-to-right, which is why this is no longer pinned right-to-left: pinned, an
 * English line had its full stop jump to the wrong end.
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
            style = VmTheme.typography.bodySm.copy(textDirection = TextDirection.Content),
            color = VmTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(VmSizes.bubbleMaxWidthFraction)
                .padding(vertical = VmSpacing.xs),
        )
    }
}
