// One composable per state of the updater's state machine: merging them to satisfy the
// per-file function count would hide the flow this file exists to make readable.
@file:Suppress("TooManyFunctions")

package ir.vmessenger.feature.settings.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.vmessenger.core.designsystem.component.VMessengerScaffold
import ir.vmessenger.core.designsystem.error.toUiText
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.feature.settings.R

/**
 * In-app updates.
 *
 * The screen checks on entry without forcing (the throttle still applies) so opening it is
 * cheap, and the button forces. The install step goes through the system package installer;
 * if it refuses — which is what happens when the release is signed by a different key than
 * the installed build — the screen switches to offering the file instead of failing quietly.
 */
@Composable
fun UpdateRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saveDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(APK_MIME),
    ) { uri ->
        val path = (state as? UpdateUiState.ReinstallRequired)?.path
        if (uri != null && path != null) viewModel.saveTo(path, uri.toString())
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshInstallPermission()
        onPauseOrDispose { }
    }

    VMessengerScaffold(
        title = stringResource(R.string.settings_update_title),
        onNavigateBack = onBack,
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(VmSpacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(VmSpacing.md),
        ) {
            UpdateBody(
                state = state,
                actions = UpdateActions(
                    onCheck = { viewModel.check(force = true) },
                    onDownload = viewModel::download,
                    onSkip = viewModel::skip,
                    onCancel = viewModel::cancelDownload,
                    onInstall = { path -> install(context, viewModel, path) },
                    onSaveFile = { saveDocument.launch(APK_FILE_NAME) },
                ),
            )
        }
    }
}

/** Every action the screen can take; bundled so each state's composable stays short. */
private class UpdateActions(
    val onCheck: () -> Unit,
    val onDownload: (AvailableUpdate) -> Unit,
    val onSkip: (AvailableUpdate) -> Unit,
    val onCancel: () -> Unit,
    val onInstall: (String) -> Unit,
    val onSaveFile: () -> Unit,
)

@Composable
private fun UpdateBody(state: UpdateUiState, actions: UpdateActions) {
    when (state) {
        UpdateUiState.Idle, UpdateUiState.Checking -> Progress(stringResource(R.string.settings_update_checking))
        is UpdateUiState.UpToDate -> UpToDate(state, actions)
        is UpdateUiState.Available -> Available(state.update, actions)
        is UpdateUiState.Downloading -> Downloading(state, actions)
        is UpdateUiState.Verifying -> Progress(stringResource(R.string.settings_update_verifying))
        is UpdateUiState.ReadyToInstall -> ReadyToInstall(state, actions)
        is UpdateUiState.NeedsInstallPermission -> NeedsPermission(state)
        is UpdateUiState.ReinstallRequired -> ReinstallRequired(actions)
        is UpdateUiState.Error -> Failed(state, actions)
    }
}

@Composable
private fun Progress(label: String) {
    CircularProgressIndicator()
    Text(text = label, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun UpToDate(state: UpdateUiState.UpToDate, actions: UpdateActions) {
    Text(
        text = stringResource(R.string.settings_update_up_to_date),
        style = MaterialTheme.typography.titleMedium,
    )
    state.lastCheckedLabel?.let {
        Text(
            text = stringResource(R.string.settings_update_last_checked, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = actions.onCheck) { Text(text = stringResource(R.string.settings_update_check_now)) }
}

@Composable
private fun Available(update: AvailableUpdate, actions: UpdateActions) {
    Text(
        text = stringResource(R.string.settings_update_available, VmTextFormat.persianDigits(update.versionName)),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.settings_update_size, VmTextFormat.fileSize(update.asset.sizeBytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (update.releaseNotes.isNotBlank()) {
        Text(text = update.releaseNotes, style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = { actions.onDownload(update) }, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.settings_update_download))
    }
    TextButton(onClick = { actions.onSkip(update) }) {
        Text(text = stringResource(R.string.settings_update_skip))
    }
}

@Composable
private fun Downloading(state: UpdateUiState.Downloading, actions: UpdateActions) {
    Text(
        text = stringResource(R.string.settings_update_downloading, state.sizeLabel),
        style = MaterialTheme.typography.bodyMedium,
    )
    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
    Text(
        text = VmTextFormat.percent(state.fraction),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = actions.onCancel) { Text(text = stringResource(R.string.settings_update_cancel)) }
}

@Composable
private fun ReadyToInstall(state: UpdateUiState.ReadyToInstall, actions: UpdateActions) {
    Text(
        text = stringResource(R.string.settings_update_verified),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.settings_update_verified_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { actions.onInstall(state.path) }, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.settings_update_install))
    }
}

/**
 * Android will not let a sideloaded app install anything until the user allows it for this
 * app specifically. There is no callback, so the screen re-reads the answer on every resume.
 */
@Composable
private fun NeedsPermission(state: UpdateUiState.NeedsInstallPermission) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.settings_update_permission_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.settings_update_permission_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { openInstallSettings(context) }, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.settings_update_permission_action))
    }
    Text(
        text = VmTextFormat.persianDigits(state.update.versionName),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The release is signed by a different key than the installed build, so Android refuses the
 * upgrade. Nothing the app can do fixes that — the user keeps the file, backs up, uninstalls
 * and installs by hand.
 */
@Composable
private fun ReinstallRequired(actions: UpdateActions) {
    Text(
        text = stringResource(R.string.settings_update_reinstall_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.settings_update_reinstall_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = actions.onSaveFile, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.settings_update_save_file))
    }
}

@Composable
private fun Failed(state: UpdateUiState.Error, actions: UpdateActions) {
    Text(
        text = state.error.toUiText(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Start,
    )
    OutlinedButton(onClick = actions.onCheck) { Text(text = stringResource(R.string.settings_update_retry)) }
}

/**
 * Hands the verified APK to the system installer. A refusal here is the signature mismatch
 * in practice, so the screen offers the file instead of showing an error the user cannot act on.
 */
private fun install(context: Context, viewModel: UpdateViewModel, path: String) {
    val uri = viewModel.installUri(path)
    if (uri == null) {
        viewModel.onInstallRejected(path)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), APK_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { failure ->
            if (failure is ActivityNotFoundException) viewModel.onInstallRejected(path)
        }
}

private fun openInstallSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val APK_MIME = "application/vnd.android.package-archive"
private const val APK_FILE_NAME = "vMessenger-update.apk"
