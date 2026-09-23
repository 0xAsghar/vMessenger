package ir.vmessenger.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import ir.vmessenger.core.designsystem.component.VmInputDialog
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig

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

    VmInputDialog(
        title = stringResource(R.string.settings_backup_passphrase_title),
        confirmLabel = stringResource(R.string.settings_backup_passphrase_action),
        // The button stays live and answers with the reason: a disabled button says nothing
        // about which of the two fields is wrong.
        onConfirm = {
            if (tooShort || mismatch) {
                showErrors = true
            } else {
                onConfirm(passphrase.toCharArray())
            }
        },
        onDismiss = onDismiss,
        dismissLabel = stringResource(R.string.settings_backup_cancel),
    ) {
        VmText(text = stringResource(R.string.settings_backup_passphrase_body))
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
}

@Composable
private fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
) {
    VmTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        config = VmTextFieldConfig(
            label = label,
            isError = error != null,
            supportingText = error,
            isPassword = true,
            keyboardType = KeyboardType.Password,
        ),
    )
}
