package ir.vmessenger.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.theme.VmSpacing

/**
 * Renders whichever confirmation the state asks for.
 *
 * The delete and block bodies name exactly what is lost, because neither can be undone from the
 * screen that triggered it: a deletion is final and tells the peer, and an unblock only exists in
 * settings.
 */
@Composable
internal fun ContactDialogHost(
    dialog: ContactDialog,
    callbacks: ContactDialogCallbacks,
) {
    when (dialog) {
        ContactDialog.None -> Unit
        is ContactDialog.Rename -> RenameContactDialog(
            currentName = dialog.currentName,
            onSave = callbacks.onRename,
            onDismiss = callbacks.onDismiss,
        )
        is ContactDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.contacts_delete_title),
            body = stringResource(R.string.contacts_delete_body, dialog.name),
            confirmLabel = stringResource(R.string.contacts_delete_confirm),
            onConfirm = callbacks.onConfirm,
            onDismiss = callbacks.onDismiss,
            destructive = true,
        )
        is ContactDialog.Block -> ConfirmDialog(
            title = stringResource(R.string.contacts_block_title, dialog.name),
            body = stringResource(R.string.contacts_block_body),
            confirmLabel = stringResource(R.string.contacts_block_confirm),
            onConfirm = callbacks.onConfirm,
            onDismiss = callbacks.onDismiss,
            destructive = true,
        )
        is ContactDialog.Unblock -> ConfirmDialog(
            title = stringResource(R.string.contacts_unblock_title, dialog.name),
            body = stringResource(R.string.contacts_unblock_body),
            confirmLabel = stringResource(R.string.contacts_unblock_confirm),
            onConfirm = callbacks.onConfirm,
            onDismiss = callbacks.onDismiss,
        )
        is ContactDialog.RejectRequest -> ConfirmDialog(
            title = stringResource(R.string.contacts_request_reject_title),
            body = stringResource(R.string.contacts_request_reject_body, dialog.name),
            confirmLabel = stringResource(R.string.contacts_request_reject_confirm),
            onConfirm = callbacks.onConfirm,
            onDismiss = callbacks.onDismiss,
            destructive = true,
        )
    }
}

/** Local alias editor. The name is only stored on this device, which the body says. */
@Composable
private fun RenameContactDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var alias by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val trimmed = alias.trim()
    val valid = trimmed.length in ContactActions.MIN_ALIAS_LENGTH..ContactActions.MAX_ALIAS_LENGTH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.contacts_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.sm)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { if (it.length <= ContactActions.MAX_ALIAS_LENGTH) alias = it },
                    label = { Text(text = stringResource(R.string.contacts_rename_label)) },
                    singleLine = true,
                    isError = !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.contacts_rename_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(trimmed) }, enabled = valid) {
                Text(text = stringResource(R.string.contacts_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.contacts_cancel)) }
        },
        shape = MaterialTheme.shapes.large,
    )
}
