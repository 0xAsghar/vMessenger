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
import ir.vmessenger.core.designsystem.component.VmButton
import ir.vmessenger.core.designsystem.component.VmLinearProgress
import ir.vmessenger.core.designsystem.component.VmOutlinedButton
import ir.vmessenger.core.designsystem.component.VmProgressIndicator
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.component.VmTextButton
import ir.vmessenger.core.designsystem.error.toUiText
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.feature.settings.R

/**
 * In-app updates.
 *
 * The ViewModel checks on entry without forcing (the throttle still applies), so opening
 * the screen is cheap and usually answers from stored state; the button forces a call.
 *
 * The install step goes through the system package installer. If it refuses — which is what
 * happens when the release is signed by a different key than the installed build — the screen
 * switches to offering the file instead of failing quietly.
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
    val onCancel: (AvailableUpdate) -> Unit,
    val onInstall: (String) -> Unit,
    val onSaveFile: () -> Unit,
)

@Composable
private fun UpdateBody(state: UpdateUiState, actions: UpdateActions) {
    when (state) {
        UpdateUiState.Checking -> Progress(stringResource(R.string.settings_update_checking))
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
    VmProgressIndicator()
    VmText(text = label, style = VmTheme.typography.bodyMd)
}

@Composable
private fun UpToDate(state: UpdateUiState.UpToDate, actions: UpdateActions) {
    VmText(
        text = stringResource(R.string.settings_update_up_to_date),
        style = VmTheme.typography.bodyLgMedium,
    )
    state.lastCheckedLabel?.let {
        VmText(
            text = stringResource(R.string.settings_update_last_checked, it),
            style = VmTheme.typography.bodySm,
            color = VmTheme.colors.textSecondary,
        )
    }
    VmButton(text = stringResource(R.string.settings_update_check_now), onClick = actions.onCheck)
}

@Composable
private fun Available(update: AvailableUpdate, actions: UpdateActions) {
    VmText(
        text = stringResource(R.string.settings_update_available, VmTextFormat.digits(update.versionName)),
        style = VmTheme.typography.bodyLgMedium,
    )
    VmText(
        text = stringResource(R.string.settings_update_size, VmTextFormat.fileSize(update.asset.sizeBytes)),
        style = VmTheme.typography.bodySm,
        color = VmTheme.colors.textSecondary,
    )
    if (update.releaseNotes.isNotBlank()) {
        VmText(text = update.releaseNotes, style = VmTheme.typography.bodySm)
    }
    VmButton(
        text = stringResource(R.string.settings_update_download),
        onClick = { actions.onDownload(update) },
        modifier = Modifier.fillMaxWidth(),
    )
    VmTextButton(text = stringResource(R.string.settings_update_skip), onClick = { actions.onSkip(update) })
}

@Composable
private fun Downloading(state: UpdateUiState.Downloading, actions: UpdateActions) {
    VmText(
        text = stringResource(R.string.settings_update_downloading, state.sizeLabel),
        style = VmTheme.typography.bodyMd,
    )
    VmLinearProgress(progress = state.fraction, modifier = Modifier.fillMaxWidth())
    VmText(
        text = VmTextFormat.percent(state.fraction),
        style = VmTheme.typography.bodySm,
        color = VmTheme.colors.textSecondary,
    )
    VmTextButton(text = stringResource(R.string.settings_update_cancel), onClick = { actions.onCancel(state.update) })
}

@Composable
private fun ReadyToInstall(state: UpdateUiState.ReadyToInstall, actions: UpdateActions) {
    VmText(
        text = stringResource(R.string.settings_update_verified),
        style = VmTheme.typography.bodyLgMedium,
    )
    VmText(
        text = stringResource(R.string.settings_update_verified_body),
        style = VmTheme.typography.bodySm,
        color = VmTheme.colors.textSecondary,
    )
    VmButton(
        text = stringResource(R.string.settings_update_install),
        onClick = { actions.onInstall(state.path) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Android will not let a sideloaded app install anything until the user allows it for this
 * app specifically. There is no callback, so the screen re-reads the answer on every resume.
 */
@Composable
private fun NeedsPermission(state: UpdateUiState.NeedsInstallPermission) {
    val context = LocalContext.current
    VmText(
        text = stringResource(R.string.settings_update_permission_title),
        style = VmTheme.typography.bodyLgMedium,
    )
    VmText(
        text = stringResource(R.string.settings_update_permission_body),
        style = VmTheme.typography.bodySm,
        color = VmTheme.colors.textSecondary,
    )
    VmButton(
        text = stringResource(R.string.settings_update_permission_action),
        onClick = { openInstallSettings(context) },
        modifier = Modifier.fillMaxWidth(),
    )
    VmText(
        text = VmTextFormat.digits(state.update.versionName),
        style = VmTheme.typography.bodyXsMedium,
        color = VmTheme.colors.textSecondary,
    )
}

/**
 * The release is signed by a different key than the installed build, so Android refuses the
 * upgrade. Nothing the app can do fixes that — the user keeps the file, backs up, uninstalls
 * and installs by hand.
 */
@Composable
private fun ReinstallRequired(actions: UpdateActions) {
    VmText(
        text = stringResource(R.string.settings_update_reinstall_title),
        style = VmTheme.typography.bodyLgMedium,
    )
    VmText(
        text = stringResource(R.string.settings_update_reinstall_body),
        style = VmTheme.typography.bodySm,
        color = VmTheme.colors.textSecondary,
    )
    VmButton(
        text = stringResource(R.string.settings_update_save_file),
        onClick = actions.onSaveFile,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Failed(state: UpdateUiState.Error, actions: UpdateActions) {
    VmText(
        text = state.error.toUiText(),
        style = VmTheme.typography.bodyMd,
        color = VmTheme.colors.textCritical,
        textAlign = TextAlign.Start,
    )
    VmOutlinedButton(text = stringResource(R.string.settings_update_retry), onClick = actions.onCheck)
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
