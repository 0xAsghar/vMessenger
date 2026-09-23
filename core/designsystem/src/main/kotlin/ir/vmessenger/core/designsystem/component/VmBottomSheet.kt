package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The action sheet: a [VmModalSheet] with its title in the same place at the same weight every
 * time, over a list of actions.
 */
@Composable
fun VmBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    VmModalSheet(onDismissRequest = onDismiss, modifier = modifier) {
        // Blank means no header at all rather than an empty band: a sheet opened on a voice
        // message has nothing to quote. One line always — a title here can be a message
        // preview, and the actions are what the sheet is for.
        if (title.isNotBlank()) {
            VmText(
                text = title,
                style = VmTheme.typography.bodyLgMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.sm)
                    .semantics { heading() },
            )
        }
        content()
    }
}
