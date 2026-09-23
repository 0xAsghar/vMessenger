package ir.vmessenger.feature.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmProgressIndicator
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextField
import ir.vmessenger.core.designsystem.component.VmTextFieldConfig
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
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
        VmText(
            text = stringResource(R.string.restore_backup_confirm_title),
            modifier = Modifier.fillMaxWidth(),
            style = VmTheme.typography.headingLg,
            color = VmTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        VmText(
            text = stringResource(R.string.restore_backup_confirm_body),
            modifier = Modifier.fillMaxWidth(),
            style = VmTheme.typography.bodyMd,
            color = VmTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
    BackupHeaderCard(header = state.header)
    VmTextField(
        value = state.passphrase,
        onValueChange = onPassphraseChange,
        modifier = Modifier.fillMaxWidth(),
        config = VmTextFieldConfig(
            label = stringResource(R.string.restore_backup_passphrase_label),
            isError = state.failure != null,
            supportingText = state.failure?.message(),
            isPassword = true,
            keyboardType = KeyboardType.Password,
        ),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        VmButton(
            text = stringResource(R.string.restore_backup_confirm_action),
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.passphrase.isNotEmpty(),
        )
        VmOutlinedButton(
            text = stringResource(R.string.restore_backup_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** What the file claims about itself, before a passphrase is spent on it. */
@Composable
private fun BackupHeaderCard(header: BackupHeaderInfo) {
    VmSurface(shape = VmShapes.card, color = VmTheme.colors.bgSubtle, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.xxs),
        ) {
            VmText(
                text = stringResource(R.string.restore_backup_file_section),
                style = VmTheme.typography.bodySmMedium,
                color = VmTheme.colors.textSecondary,
            )
            VmText(
                text = stringResource(
                    R.string.restore_backup_format_version,
                    VmTextFormat.digits(header.version.toString()),
                ),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textPrimary,
            )
            VmText(
                text = stringResource(
                    R.string.restore_backup_kdf_params,
                    VmTextFormat.digits(header.kdfOps.toString()),
                    VmTextFormat.digits((header.kdfMemBytes / BYTES_PER_MIB).toString()),
                ),
                style = VmTheme.typography.bodyMd,
                color = VmTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun RestoreProgressStep(inspecting: Boolean) {
    VmProgressIndicator()
    VmText(
        text = stringResource(
            if (inspecting) R.string.restore_backup_reading else R.string.restore_backup_restoring,
        ),
        style = VmTheme.typography.bodyMd,
        color = VmTheme.colors.textSecondary,
    )
}

@Composable
internal fun RestoreFailedStep(failure: RestoreFailure, onBack: () -> Unit) {
    VmText(
        text = failure.message(),
        modifier = Modifier.fillMaxWidth(),
        style = VmTheme.typography.bodyLg,
        color = VmTheme.colors.textCritical,
        textAlign = TextAlign.Center,
    )
    VmButton(text = stringResource(R.string.restore_backup_back), onClick = onBack)
}

@Composable
internal fun RestoreFailure.message(): String = when (this) {
    RestoreFailure.WrongPassphrase -> stringResource(R.string.restore_backup_error_wrong_passphrase)
    RestoreFailure.UnreadableFile -> stringResource(R.string.restore_backup_error_unreadable)
    RestoreFailure.PassphraseTooShort -> stringResource(R.string.restore_backup_error_passphrase_short)
    is RestoreFailure.Rejected -> message
    RestoreFailure.Unknown -> stringResource(R.string.restore_backup_error_generic)
}
