package ir.vmessenger.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

internal const val BACKUP_PASSPHRASE_MIN_CHARS = 8

/**
 * Asks for the backup passphrase twice. [onConfirm] receives a fresh [CharArray]
 * that the caller owns and must zero after use.
 */
@Composable
internal fun BackupPassphraseDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    val tooShort = passphrase.length < BACKUP_PASSPHRASE_MIN_CHARS
    val mismatch = passphrase != confirmation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_backup_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_backup_passphrase_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PassphraseField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.settings_backup_passphrase_label),
                    error = stringResource(R.string.settings_backup_passphrase_too_short)
                        .takeIf { showErrors && tooShort },
                )
                PassphraseField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = stringResource(R.string.settings_backup_passphrase_confirm_label),
                    error = stringResource(R.string.settings_backup_passphrase_mismatch)
                        .takeIf { showErrors && !tooShort && mismatch },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (tooShort || mismatch) {
                        showErrors = true
                    } else {
                        onConfirm(passphrase.toCharArray())
                    }
                },
            ) {
                Text(text = stringResource(R.string.settings_backup_passphrase_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_backup_cancel))
            }
        },
    )
}

@Composable
private fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
