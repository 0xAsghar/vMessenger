package ir.vmessenger.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.LocalAppObscured
import ir.vmessenger.core.designsystem.R

/**
 * The single confirmation dialog used for every irreversible action (delete a contact, leave a
 * group, wipe). [destructive] only tints the confirm label — the wording still has to say what
 * will be lost.
 */
@Suppress("LongParameterList") // Compose slot API: title, body, two labels and two callbacks.
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    dismissLabel: String = stringResource(R.string.vm_cancel),
) {
    // Nothing above the lock. A dialog and a modal sheet each live in their own window, so the
    // lock overlay — which is a composable inside the app's own window — does not cover them: a
    // sheet left open when the app locked stayed on top of the lock screen and stayed fully
    // interactive, which is a way past a lock rather than a cosmetic flaw. Not composing rather
    // than dismissing, so it is still there when the user comes back.
    if (LocalAppObscured.current) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = dismissLabel) }
        },
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    )
}
