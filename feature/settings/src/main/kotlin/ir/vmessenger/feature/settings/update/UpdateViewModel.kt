package ir.vmessenger.feature.settings.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.domain.model.AvailableUpdate
import ir.vmessenger.domain.model.DownloadProgress
import ir.vmessenger.domain.model.UpdateCheck
import ir.vmessenger.domain.repository.UpdateRepository
import ir.vmessenger.domain.usecase.update.CheckForUpdateUseCase
import ir.vmessenger.domain.usecase.update.DownloadUpdateUseCase
import ir.vmessenger.domain.usecase.update.ObserveUpdateStatusUseCase
import ir.vmessenger.domain.usecase.update.SaveUpdateApkUseCase
import ir.vmessenger.domain.usecase.update.SkipUpdateVersionUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The updater screen.
 *
 * A download is a [Job] the screen can abandon: leaving mid-download cancels it and the
 * partial file is deleted, because a half-written APK is worth nothing and keeping one
 * would only invite installing it.
 */
@HiltViewModel
// One entry point per step of a linear flow (check, download, install, skip, save); a bag of
// lambdas would hide the state machine this class exists to be.
@Suppress("TooManyFunctions", "LongParameterList")
class UpdateViewModel @Inject constructor(
    private val checkForUpdate: CheckForUpdateUseCase,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val skipVersion: SkipUpdateVersionUseCase,
    private val saveApk: SaveUpdateApkUseCase,
    private val updateRepository: UpdateRepository,
    observeStatus: ObserveUpdateStatusUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Checking)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        // Opening the screen is itself a request to know. Not forced, so the daily throttle
        // still applies and the answer usually comes from the stored outcome, not the network.
        check(force = false)
    }

    /** Formatted moment of the last successful check, for the "last checked" line. */
    val lastChecked: StateFlow<String?> = observeStatus()
        .map { status -> status.lastCheckedAtUnixMs?.let { VmDateFormat.dayAndTime(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    private var downloadJob: Job? = null
    private var checkJob: Job? = null

    /**
     * [force] is what the user's own button passes; the automatic check on entry does not.
     *
     * The guard is the in-flight job, not the visible state: the screen *starts* in
     * [UpdateUiState.Checking], so gating on that would make the user's first tap on
     * "check now" do nothing.
     */
    fun check(force: Boolean) {
        if (checkJob?.isActive == true && !force) return
        checkJob?.cancel()
        _state.value = UpdateUiState.Checking
        checkJob = viewModelScope.launch {
            _state.value = when (val result = checkForUpdate(force)) {
                is AppResult.Success -> result.data.toUiState(lastChecked.value)
                is AppResult.Error -> UpdateUiState.Error(result.error)
            }
        }
    }

    fun download(update: AvailableUpdate) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            downloadUpdate(update).collect { progress -> _state.value = progress.toUiState(update) }
        }
    }

    /**
     * Cancels an in-flight download; the repository deletes the partial file. The screen goes
     * back to the offer, not to nothing — the update is still there, the user just said "not
     * right now".
     */
    fun cancelDownload(update: AvailableUpdate) {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Available(update)
    }

    fun skip(update: AvailableUpdate) {
        viewModelScope.launch {
            skipVersion(update.versionName)
            _state.value = UpdateUiState.UpToDate(lastChecked.value)
        }
    }

    /**
     * Re-read on every resume: the user may have just granted "install unknown apps" in
     * Settings, and Android offers no callback for that.
     */
    fun refreshInstallPermission() {
        val current = _state.value
        if (current is UpdateUiState.NeedsInstallPermission && updateRepository.canInstallPackages()) {
            _state.value = UpdateUiState.ReadyToInstall(current.update, current.path)
        }
    }

    /** Content Uri for the package installer, or null when the verified file has gone. */
    fun installUri(path: String): String? = updateRepository.installUri(path)

    /** The signer differs, so Android will refuse the upgrade: offer the file instead. */
    fun onInstallRejected(path: String) {
        _state.value = UpdateUiState.ReinstallRequired(path)
    }

    fun saveTo(path: String, destinationUri: String) {
        viewModelScope.launch {
            if (saveApk(path, destinationUri) is AppResult.Error) {
                _state.value = UpdateUiState.Error(AppError.Unknown("could not save the update file"))
            }
        }
    }

    private fun UpdateCheck.toUiState(lastCheckedLabel: String?): UpdateUiState = when (this) {
        UpdateCheck.UpToDate -> UpdateUiState.UpToDate(lastCheckedLabel)
        is UpdateCheck.Available -> UpdateUiState.Available(update)
    }

    private fun DownloadProgress.toUiState(update: AvailableUpdate): UpdateUiState = when (this) {
        is DownloadProgress.Downloading ->
            UpdateUiState.Downloading(update, fraction, VmDateFormat.fileSize(totalBytes))
        DownloadProgress.Verifying -> UpdateUiState.Verifying(update)
        is DownloadProgress.VerifiedFile ->
            if (updateRepository.canInstallPackages()) {
                UpdateUiState.ReadyToInstall(update, path)
            } else {
                UpdateUiState.NeedsInstallPermission(update, path)
            }
        is DownloadProgress.Failed -> UpdateUiState.Error(error)
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
