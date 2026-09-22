package ir.vmessenger.feature.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import ir.vmessenger.core.designsystem.component.SettingsSection
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.BackupHeaderInfo

private const val BYTES_PER_MIB = 1024L * 1024L

/** Header info + passphrase entry; the last failed attempt is shown under the field. */
@Composable
internal fun RestoreConfirmStep(
    state: CreateIdentityUiState.RestoreConfirm,
    onPassphraseChange: (String) -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.restore_backup_confirm_title),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.restore_backup_confirm_body),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    BackupHeaderCard(header = state.header)
    OutlinedTextField(
        value = state.passphrase,
        onValueChange = onPassphraseChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.restore_backup_passphrase_label)) },
        isError = state.failure != null,
        supportingText = state.failure?.let { failure -> { Text(failure.message()) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Button(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.passphrase.isNotEmpty(),
        ) {
            Text(text = stringResource(R.string.restore_backup_confirm_action))
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.restore_backup_cancel))
        }
    }
}

/** What the file claims about itself, before a passphrase is spent on it. */
@Composable
private fun BackupHeaderCard(header: BackupHeaderInfo) {
    SettingsSection(title = stringResource(R.string.restore_backup_file_section)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.xxs),
        ) {
            Text(
                text = stringResource(
                    R.string.restore_backup_format_version,
                    VmTextFormat.digits(header.version.toString()),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.restore_backup_kdf_params,
                    VmTextFormat.digits(header.kdfOps.toString()),
                    VmTextFormat.digits((header.kdfMemBytes / BYTES_PER_MIB).toString()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RestoreProgressStep(inspecting: Boolean) {
    CircularProgressIndicator()
    Text(
        text = stringResource(
            if (inspecting) R.string.restore_backup_reading else R.string.restore_backup_restoring,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun RestoreFailedStep(failure: RestoreFailure, onBack: () -> Unit) {
    Text(
        text = failure.message(),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onBack) {
        Text(text = stringResource(R.string.restore_backup_back))
    }
}

@Composable
internal fun RestoreFailure.message(): String = when (this) {
    RestoreFailure.WrongPassphrase -> stringResource(R.string.restore_backup_error_wrong_passphrase)
    RestoreFailure.UnreadableFile -> stringResource(R.string.restore_backup_error_unreadable)
    RestoreFailure.PassphraseTooShort -> stringResource(R.string.restore_backup_error_passphrase_short)
    is RestoreFailure.Rejected -> message
    RestoreFailure.Unknown -> stringResource(R.string.restore_backup_error_generic)
}
