package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * Centred `امروز` / `دیروز` / Jalali date pill between two days of messages — and the group's own
 * system lines, which are the same shape of centred, unowned label.
 *
 * Both are Persian by construction, so the paragraph direction is pinned rather than taken from
 * the first strong character. That matters for a membership line like «Ali به گروه اضافه شد»:
 * content direction would let a Latin name flip the whole sentence. Newly written lines isolate
 * the name themselves; pinning here covers the ones already in the database.
 */
@Composable
fun DateSeparator(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = VmSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Rtl),
                modifier = Modifier.padding(horizontal = VmSpacing.md, vertical = VmSpacing.xs),
            )
        }
    }
}
