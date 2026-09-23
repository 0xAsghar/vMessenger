package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * Centred `امروز` / `دیروز` / Jalali date between two days of messages: plain medium text on the
 * canvas, no pill, as Element X marks a day. A heading to a screen reader, so a long conversation
 * can be walked a day at a time.
 *
 * The label is Persian by construction, so the paragraph direction is pinned rather than taken
 * from the first strong character.
 */
@Composable
fun DateSeparator(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = VmSpacing.lg, bottom = VmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        VmText(
            text = label,
            style = VmTheme.typography.bodySmMedium.copy(textDirection = TextDirection.Content),
            color = VmTheme.colors.textSecondary,
            modifier = Modifier.semantics { heading() },
        )
    }
}
