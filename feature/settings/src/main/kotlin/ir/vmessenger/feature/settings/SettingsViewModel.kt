package ir.vmessenger.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppBuildInfo
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.domain.usecase.identity.ExportIdentityBackupUseCase
import ir.vmessenger.domain.usecase.identity.ObserveIdentityUseCase
import ir.vmessenger.domain.usecase.settings.SecureWipeUseCase
import ir.vmessenger.domain.usecase.update.ObserveUpdateStatusUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** The settings header: who this device is, as the user sees themselves. */
data class SettingsProfile(
    val displayName: String,
    val userHash: String,
    /** Identicon seed. */
    val identityHash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SettingsProfile
        return displayName == other.displayName &&
            userHash == other.userHash &&
            identityHash.contentEquals(other.identityHash)
    }

    override fun hashCode(): Int =
        31 * (31 * displayName.hashCode() + userHash.hashCode()) + identityHash.contentHashCode()
}

/** Progress of the "create backup file" action shown inline in the backup section. */
sealed class BackupExportStatus {
    data object Idle : BackupExportStatus()
    data object InProgress : BackupExportStatus()
    data object Saved : BackupExportStatus()
    data class Failed(val failure: BackupExportFailure) : BackupExportStatus()
}

sealed class BackupExportFailure {
    /** The domain layer refused to build the bundle; [message] is user-facing. */
    data class Bundle(val message: String) : BackupExportFailure()

    /** The bundle was built but could not be written to the chosen document. */
    data object Write : BackupExportFailure()
}

// One screen with independent sections (theme, privacy toggles, backup export,
// secure wipe), each contributing its own small handler; splitting them across
// ViewModels would only fragment a single settings screen's state.
@Suppress("TooManyFunctions", "LongParameterList")
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    appBuildInfo: AppBuildInfo,
    private val themePreferences: ThemePreferences,
    private val privacyPreferences: PrivacyPreferences,
    private val exportIdentityBackupUseCase: ExportIdentityBackupUseCase,
    private val secureWipeUseCase: SecureWipeUseCase,
    observeIdentity: ObserveIdentityUseCase,
    observeUpdateStatus: ObserveUpdateStatusUseCase,
) : ViewModel() {
    /**
     * The profile header. Null only in the instant between a secure wipe and the app
     * restarting into onboarding.
     */
    val profile: StateFlow<SettingsProfile?> = observeIdentity()
        .map { identity ->
            identity?.let { SettingsProfile(it.displayName, it.userHash, it.identityHash) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the last update check found something. Read from stored state, never from the
     * network, so opening settings costs nothing.
     */
    val updateAvailable: StateFlow<Boolean> = observeUpdateStatus()
        .map { it.hasUpdate }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val themeMode: StateFlow<ThemeMode> = themePreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val screenSecurityEnabled: StateFlow<Boolean> = privacyPreferences.screenSecurityEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val hideNotificationContent: StateFlow<Boolean> = privacyPreferences.hideNotificationContent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sendReadReceipts: StateFlow<Boolean> = privacyPreferences.sendReadReceipts
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PrivacyPreferences.DEFAULT_SEND_READ_RECEIPTS,
        )

    /** The debug row is always there in a debug build, and in release only once developer mode is unlocked. */
    val developerToolsVisible: StateFlow<Boolean> = privacyPreferences.developerModeEnabled
        .map { enabled -> appBuildInfo.isDebug || enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), appBuildInfo.isDebug)

    private val _backupExportStatus = MutableStateFlow<BackupExportStatus>(BackupExportStatus.Idle)
    val backupExportStatus: StateFlow<BackupExportStatus> = _backupExportStatus.asStateFlow()

    private val _wipeInProgress = MutableStateFlow(false)

    /** True from the moment the wipe starts until the process exits; the screen blocks while it is set. */
    val wipeInProgress: StateFlow<Boolean> = _wipeInProgress.asStateFlow()

    /**
     * Passphrase staged by [beginExport] while the system file picker is open. Lives here (not in
     * composition state) so a configuration change during the picker does not lose it, and never in
     * saved state so it is not written to a Bundle. Consumed by [exportTo], zeroed in [onCleared].
     */
    private var stagedPassphrase: CharArray? = null

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferences.setThemeMode(mode) }
    }

    fun setScreenSecurity(enabled: Boolean) {
        viewModelScope.launch { privacyPreferences.setScreenSecurityEnabled(enabled) }
    }

    fun setHideNotificationContent(enabled: Boolean) {
        viewModelScope.launch { privacyPreferences.setHideNotificationContent(enabled) }
    }

    fun setSendReadReceipts(enabled: Boolean) {
        viewModelScope.launch { privacyPreferences.setSendReadReceipts(enabled) }
    }

    /**
     * Irreversible; the caller must have confirmed with the user. The app
     * restarts at the end, so this never completes normally. The screen blocks
     * on [wipeInProgress] while it runs: the wipe itself cannot be cancelled,
     * and letting the user keep tapping a UI whose database is being deleted
     * only produces errors.
     */
    fun secureWipe() {
        if (_wipeInProgress.value) return
        _wipeInProgress.value = true
        viewModelScope.launch { secureWipeUseCase() }
    }

    /**
     * Stages [passphrase] for the export that follows once the user picks a destination with
     * `ACTION_CREATE_DOCUMENT`. Takes ownership of [passphrase]; any previously staged one is zeroed.
     */
    fun beginExport(passphrase: CharArray) {
        clearStagedPassphrase()
        if (_backupExportStatus.value is BackupExportStatus.InProgress) {
            passphrase.fill(NUL)
            return
        }
        stagedPassphrase = passphrase
    }

    /**
     * Builds the encrypted bundle with the passphrase staged by [beginExport] and writes it to [uri]
     * (the document created by the user). A null [uri] means the picker was cancelled: the staged
     * passphrase is zeroed and the status is left alone. If nothing is staged (the picker may still
     * have created an empty document) the user sees a write failure instead of silence. The
     * passphrase is zeroed once the export finished, whether or not it succeeded.
     */
    fun exportTo(uri: Uri?) {
        val passphrase = stagedPassphrase
        stagedPassphrase = null
        when {
            uri == null -> passphrase?.fill(NUL)
            passphrase == null -> _backupExportStatus.value = BackupExportStatus.Failed(BackupExportFailure.Write)
            _backupExportStatus.value is BackupExportStatus.InProgress -> passphrase.fill(NUL)
            else -> {
                _backupExportStatus.value = BackupExportStatus.InProgress
                viewModelScope.launch {
                    try {
                        _backupExportStatus.value = when (val result = exportIdentityBackupUseCase(passphrase)) {
                            is AppResult.Success -> writeBundle(uri, result.data)
                            is AppResult.Error ->
                                BackupExportStatus.Failed(BackupExportFailure.Bundle(result.error.message))
                        }
                    } finally {
                        passphrase.fill(NUL)
                    }
                }
            }
        }
    }

    fun dismissBackupStatus() {
        _backupExportStatus.value = BackupExportStatus.Idle
    }

    override fun onCleared() {
        clearStagedPassphrase()
        super.onCleared()
    }

    private fun clearStagedPassphrase() {
        stagedPassphrase?.fill(NUL)
        stagedPassphrase = null
    }

    private suspend fun writeBundle(uri: Uri, bundle: ByteArray): BackupExportStatus =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)
                    ?.use { output -> output.write(bundle) }
                    ?: error("cannot open $uri")
            }.fold(
                onSuccess = { BackupExportStatus.Saved },
                onFailure = { BackupExportStatus.Failed(BackupExportFailure.Write) },
            )
        }

    companion object {
        private const val NUL = '\u0000'

        /** Suggested document name for the system file picker, e.g. `vmessenger-backup-20260911.vmb`. */
        fun suggestedBackupFileName(date: LocalDate = LocalDate.now()): String =
            "vmessenger-backup-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.vmb"
    }
}
