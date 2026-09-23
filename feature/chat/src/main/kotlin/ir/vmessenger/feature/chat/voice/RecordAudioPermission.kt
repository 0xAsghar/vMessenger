package ir.vmessenger.feature.chat.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import ir.vmessenger.core.designsystem.component.VmSnackbarHostState
import ir.vmessenger.core.designsystem.component.VmSnackbarResult
import ir.vmessenger.feature.chat.R
import kotlinx.coroutines.launch

/** The three answers the system can give, plus the one it cannot: never asked. */
internal enum class MicPermission { Granted, NotAsked, Denied, PermanentlyDenied }

/** The message and the way out of one denied state. */
private class MicPrompt(val message: String, val action: String, val onAction: () -> Unit)

/**
 * The microphone permission, together with the only two things the mic button can do about it:
 * ask, or explain why asking is no longer possible.
 */
@Stable
internal class RecordAudioPermission(
    val state: MicPermission,
    private val onRequest: () -> Unit,
    private val onExplain: (MicPermission) -> Unit,
) {
    /**
     * True when recording may start. Otherwise this asks — or, once Android has stopped
     * showing the dialog, points the user at Settings — and the press is dropped.
     */
    fun ensureGranted(): Boolean {
        if (state == MicPermission.Granted) return true
        if (state == MicPermission.NotAsked) onRequest() else onExplain(state)
        return false
    }
}

/**
 * `RECORD_AUDIO` as state, re-read on every resume so the trip to Settings that the
 * permanently-denied branch sends the user on is noticed when they come back.
 *
 * The explanation rides on [snackbar] rather than a dialog: the composer is at the bottom of
 * the screen, which is exactly where the snackbar and its action appear.
 */
@Composable
internal fun rememberRecordAudioPermission(snackbar: VmSnackbarHostState): RecordAudioPermission {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    var asked by rememberSaveable { mutableStateOf(false) }
    var state by remember { mutableStateOf(resolvePermission(context, activity, asked = false)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        state = resolvePermission(context, activity, asked = true)
    }
    LifecycleResumeEffect(context, activity, asked) {
        state = resolvePermission(context, activity, asked)
        onPauseOrDispose { }
    }

    val rationale = stringResource(R.string.feature_chat_voice_permission_rationale)
    val blocked = stringResource(R.string.feature_chat_voice_permission_blocked)
    val allow = stringResource(R.string.feature_chat_voice_permission_allow)
    val openSettings = stringResource(R.string.feature_chat_voice_permission_settings)

    return remember(state, launcher, context, snackbar) {
        val ask = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
        RecordAudioPermission(
            state = state,
            onRequest = ask,
            onExplain = { denial ->
                val prompt = if (denial == MicPermission.PermanentlyDenied) {
                    MicPrompt(blocked, openSettings) { context.openAppSettings() }
                } else {
                    MicPrompt(rationale, allow, ask)
                }
                scope.launch { explain(snackbar, prompt) }
            },
        )
    }
}

private suspend fun explain(snackbar: VmSnackbarHostState, prompt: MicPrompt) {
    val result = snackbar.showSnackbar(message = prompt.message, actionLabel = prompt.action)
    if (result == VmSnackbarResult.ActionPerformed) prompt.onAction()
}

/**
 * "Permanently denied" is not a flag Android exposes: it is *denied, no rationale offered, and
 * we have asked at least once*. [asked] is saveable so a process death does not turn that back
 * into a first ask.
 */
private fun resolvePermission(context: Context, activity: Activity?, asked: Boolean): MicPermission = when {
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED -> MicPermission.Granted
    activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO) ->
        MicPermission.Denied
    asked -> MicPermission.PermanentlyDenied
    else -> MicPermission.NotAsked
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
