package ir.vmessenger.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.usecase.identity.ExportIdentityBackupUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themePreferences: ThemePreferences,
    private val privacyPreferences: PrivacyPreferences,
    private val identityRepository: IdentityRepository,
    private val exportIdentityBackupUseCase: ExportIdentityBackupUseCase,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = themePreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val screenSecurityEnabled: StateFlow<Boolean> = privacyPreferences.screenSecurityEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val hideNotificationContent: StateFlow<Boolean> = privacyPreferences.hideNotificationContent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _backupExportStatus = MutableStateFlow<BackupExportStatus>(BackupExportStatus.Idle)
    val backupExportStatus: StateFlow<BackupExportStatus> = _backupExportStatus.asStateFlow()

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

    fun secureWipe() {
        viewModelScope.launch { identityRepository.wipeIdentity() }
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
