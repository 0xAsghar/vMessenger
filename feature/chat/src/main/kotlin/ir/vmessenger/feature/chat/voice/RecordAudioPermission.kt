package ir.vmessenger.feature.chat.voice

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import ir.vmessenger.core.designsystem.component.VmSnackbarHostState
import ir.vmessenger.core.designsystem.component.VmSnackbarResult
import ir.vmessenger.core.designsystem.foundation.PermissionStatus
import ir.vmessenger.core.designsystem.foundation.rememberRuntimePermission
import ir.vmessenger.feature.chat.R
import kotlinx.coroutines.launch

/** The message and the way out of one denied state. */
private class MicPrompt(val message: String, val action: String, val onAction: () -> Unit)

/**
 * The microphone permission, together with the only two things the mic button can do about it:
 * ask, or explain why asking is no longer possible.
 */
@Stable
internal class RecordAudioPermission(
    val state: PermissionStatus,
    private val onRequest: () -> Unit,
    private val onExplain: (PermissionStatus) -> Unit,
) {
    /**
     * True when recording may start. Otherwise this asks — or, once Android has stopped
     * showing the dialog, points the user at Settings — and the press is dropped.
     */
    fun ensureGranted(): Boolean {
        if (state == PermissionStatus.Granted) return true
        if (state == PermissionStatus.NotAsked) onRequest() else onExplain(state)
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
    val mic = rememberRuntimePermission(Manifest.permission.RECORD_AUDIO)
    val scope = rememberCoroutineScope()

    val rationale = stringResource(R.string.feature_chat_voice_permission_rationale)
    val blocked = stringResource(R.string.feature_chat_voice_permission_blocked)
    val allow = stringResource(R.string.feature_chat_voice_permission_allow)
    val openSettings = stringResource(R.string.feature_chat_voice_permission_settings)

    return remember(mic, snackbar) {
        RecordAudioPermission(
            state = mic.status,
            onRequest = mic::request,
            onExplain = { denial ->
                val prompt = if (denial == PermissionStatus.PermanentlyDenied) {
                    MicPrompt(blocked, openSettings, mic::openSettings)
                } else {
                    MicPrompt(rationale, allow, mic::request)
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
