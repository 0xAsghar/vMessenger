package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R

/**
 * The dialog for everything that is not a yes/no question: a field to fill in, a code to copy,
 * a page of instructions to read.
 *
 * Built on [VmDialog] like [ConfirmDialog], so the corner, the title and the button order match the
 * confirmation that may be one tap away — and so the lock guard is inherited rather than repeated.
 *
 * A null [dismissLabel] drops the second button, for a dialog whose only action is "close".
 */
@Suppress("LongParameterList") // Compose slot API: title, body, two labels, two callbacks, enablement.
@Composable
fun VmInputDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = stringResource(R.string.vm_cancel),
    content: @Composable ColumnScope.() -> Unit,
) {
    VmDialog(
        onDismissRequest = onDismiss,
        title = title,
        modifier = modifier,
        buttons = {
            if (dismissLabel != null) VmTextButton(text = dismissLabel, onClick = onDismiss)
            VmTextButton(text = confirmLabel, onClick = onConfirm, enabled = confirmEnabled)
        },
        content = content,
    )
}
