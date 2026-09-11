package ir.vmessenger.feature.identity

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.UserHashLabel
import ir.vmessenger.core.designsystem.component.UserHashShareRow
import ir.vmessenger.core.designsystem.component.UserHashText
import ir.vmessenger.domain.model.RestoreSummary
import ir.vmessenger.core.designsystem.R as DesignR

@Composable
fun CreateIdentityRoute(
    onIdentityCreated: () -> Unit,
    viewModel: CreateIdentityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val state = uiState) {
            CreateIdentityUiState.Intro -> CreateIdentityIntro(
                onContinue = viewModel::onIntroContinue,
                onRestoreFile = viewModel::onBackupFileSelected,
            )
            is CreateIdentityUiState.NameEntry -> CreateIdentityNameEntry(
                displayName = state.displayName,
                error = state.error,
                onDisplayNameChange = viewModel::onDisplayNameChange,
                onCreate = viewModel::createIdentity,
            )
            CreateIdentityUiState.Creating -> CreateIdentityLoading()
            is CreateIdentityUiState.Success -> CreateIdentitySuccess(
                userHash = state.identity.userHash,
                displayName = state.identity.displayName,
                restored = state.restored,
                onContinue = onIdentityCreated,
            )
            is CreateIdentityUiState.Error -> CreateIdentityError(
                message = state.message,
                onRetry = viewModel::retryFromError,
            )
            CreateIdentityUiState.InspectingBackup -> RestoreProgressStep(inspecting = true)
            is CreateIdentityUiState.RestoreConfirm -> RestoreConfirmStep(
                state = state,
                onPassphraseChange = viewModel::onRestorePassphraseChange,
                onRestore = viewModel::restoreBackup,
                onCancel = viewModel::cancelRestore,
            )
            CreateIdentityUiState.Restoring -> RestoreProgressStep(inspecting = false)
            is CreateIdentityUiState.RestoreFailed -> RestoreFailedStep(
                failure = state.failure,
                onBack = viewModel::cancelRestore,
            )
        }
    }
}

@Composable
private fun CreateIdentityIntro(
    onContinue: () -> Unit,
    onRestoreFile: (Uri) -> Unit,
) {
    val openBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onRestoreFile) }
    Icon(
        painter = painterResource(DesignR.drawable.ic_vmessenger_logo),
        contentDescription = stringResource(DesignR.string.vmessenger_logo),
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.create_identity_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.create_identity_body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.create_identity_action))
    }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = { openBackupDocument.launch(arrayOf("application/octet-stream", "*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.restore_backup_action))
    }
}

@Composable
private fun CreateIdentityNameEntry(
    displayName: String,
    error: String?,
    onDisplayNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Text(
        text = stringResource(R.string.create_identity_name_title),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.create_identity_name_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
    OutlinedTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.create_identity_name_label)) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), enabled = displayName.isNotBlank()) {
        Text(text = stringResource(R.string.create_identity_name_action))
    }
}

@Composable
private fun CreateIdentityLoading() {
    CircularProgressIndicator()
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(R.string.create_identity_creating))
}

@Composable
private fun CreateIdentitySuccess(
    userHash: String,
    displayName: String,
    restored: RestoreSummary?,
    onContinue: () -> Unit,
) {
    Text(
        text = stringResource(
            if (restored != null) R.string.restore_backup_success_title else R.string.create_identity_success_title,
        ),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    if (restored != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.restore_backup_summary,
                restored.contacts,
                restored.conversations,
                restored.messages,
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (displayName.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            UserHashLabel()
            UserHashText(
                text = userHash,
                modifier = Modifier.padding(top = 8.dp),
            )
            UserHashShareRow(userHash = userHash)
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.create_identity_continue))
    }
}

@Composable
private fun CreateIdentityError(message: String, onRetry: () -> Unit) {
    Text(text = message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onRetry) {
        Text(text = stringResource(R.string.create_identity_retry))
    }
}
