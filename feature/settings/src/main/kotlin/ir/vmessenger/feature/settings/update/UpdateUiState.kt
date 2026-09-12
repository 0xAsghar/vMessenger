package ir.vmessenger.feature.settings.update

import androidx.compose.runtime.Immutable
import ir.vmessenger.core.common.AppError
import ir.vmessenger.domain.model.AvailableUpdate

/**
 * The updater screen, as one state at a time.
 *
 * The two unusual ones are the honest cases. [NeedsInstallPermission] is where Android
 * stands between a sideloaded app and installing anything; [ReinstallRequired] is where the
 * release is signed by a different key than the installed build, which Android refuses to
 * treat as an upgrade — the only way forward is for the user to keep the file, uninstall
 * and install it themselves, and pretending otherwise would just fail silently.
 */
@Immutable
sealed interface UpdateUiState {
    data object Checking : UpdateUiState

    /** [lastCheckedLabel] is already formatted (Persian digits); null before the first check. */
    data class UpToDate(val lastCheckedLabel: String?) : UpdateUiState

    data class Available(val update: AvailableUpdate) : UpdateUiState

    data class Downloading(val update: AvailableUpdate, val fraction: Float, val sizeLabel: String) : UpdateUiState

    data class Verifying(val update: AvailableUpdate) : UpdateUiState

    data class ReadyToInstall(val update: AvailableUpdate, val path: String) : UpdateUiState

    /** The user must allow "install unknown apps" first; re-checked whenever the screen resumes. */
    data class NeedsInstallPermission(val update: AvailableUpdate, val path: String) : UpdateUiState

    /** Signed by a different key: back up, uninstall, install the saved file by hand. */
    data class ReinstallRequired(val path: String) : UpdateUiState

    data class Error(val error: AppError) : UpdateUiState
}
