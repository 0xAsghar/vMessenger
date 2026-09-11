package ir.vmessenger.feature.identity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    Text(
        text = stringResource(R.string.restore_backup_confirm_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.restore_backup_confirm_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(16.dp))
    BackupHeaderCard(header = state.header)
    Spacer(modifier = Modifier.height(16.dp))
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
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRestore,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.passphrase.isNotEmpty(),
    ) {
        Text(text = stringResource(R.string.restore_backup_confirm_action))
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.restore_backup_cancel))
    }
}

@Composable
private fun BackupHeaderCard(header: BackupHeaderInfo) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.restore_backup_format_version, header.version),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.restore_backup_kdf_params,
                    header.kdfOps,
                    header.kdfMemBytes / BYTES_PER_MIB,
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
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(
            if (inspecting) R.string.restore_backup_reading else R.string.restore_backup_restoring,
        ),
    )
}

@Composable
internal fun RestoreFailedStep(failure: RestoreFailure, onBack: () -> Unit) {
    Text(text = failure.message(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(16.dp))
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
