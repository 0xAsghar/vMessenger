package ir.vmessenger.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.R

/**
 * The single confirmation dialog used for every irreversible action (delete a contact, leave a
 * group, wipe). [destructive] turns the confirm label red — the wording still has to say what will
 * be lost.
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
    VmDialog(
        onDismissRequest = onDismiss,
        title = title,
        modifier = modifier,
        buttons = {
            VmTextButton(text = dismissLabel, onClick = onDismiss)
            VmTextButton(text = confirmLabel, onClick = onConfirm, destructive = destructive)
        },
    ) {
        VmText(text = body)
    }
}
