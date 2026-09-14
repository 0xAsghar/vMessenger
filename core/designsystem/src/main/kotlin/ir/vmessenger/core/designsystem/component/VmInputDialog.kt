package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * The dialog for everything that is not a yes/no question: a field to fill in, a code to copy,
 * a page of instructions to read.
 *
 * It exists so the corner radius, the title weight and the button order match [ConfirmDialog].
 * A raw `AlertDialog` inherits Material's extra-large radius, which is visibly rounder than the
 * confirmation that may be one tap away.
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
                content = content,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            if (dismissLabel != null) {
                TextButton(onClick = onDismiss) { Text(text = dismissLabel) }
            }
        },
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    )
}
