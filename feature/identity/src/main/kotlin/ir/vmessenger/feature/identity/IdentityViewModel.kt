package ir.vmessenger.feature.identity

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.BackupHeaderInfo
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.model.RestoreSummary
import ir.vmessenger.domain.usecase.identity.GenerateIdentityUseCase
import ir.vmessenger.domain.usecase.identity.GetIdentityUseCase
import ir.vmessenger.domain.usecase.identity.InspectBackupUseCase
import ir.vmessenger.domain.usecase.identity.RestoreIdentityBackupUseCase
import ir.vmessenger.domain.usecase.identity.UpdateDisplayNameUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

sealed class CreateIdentityUiState {
    data object Intro : CreateIdentityUiState()
    data class NameEntry(val displayName: String = "", val error: String? = null) : CreateIdentityUiState()
    data object Creating : CreateIdentityUiState()
    data class Success(val identity: Identity, val restored: RestoreSummary? = null) : CreateIdentityUiState()
    data class Error(val message: String) : CreateIdentityUiState()

    /** The chosen backup file is being read and its header inspected. */
    data object InspectingBackup : CreateIdentityUiState()

    /** Header is valid; waiting for the passphrase. [failure] is the last failed attempt, if any. */
    data class RestoreConfirm(
        val header: BackupHeaderInfo,
        val passphrase: String = "",
        val failure: RestoreFailure? = null,
    ) : CreateIdentityUiState()

    data object Restoring : CreateIdentityUiState()

    /** The file could not be used at all; the user goes back to [Intro]. */
    data class RestoreFailed(val failure: RestoreFailure) : CreateIdentityUiState()
}

/** Why a restore attempt failed; mapped to user-facing text by the UI. */
sealed class RestoreFailure {
    data object WrongPassphrase : RestoreFailure()
    data object UnreadableFile : RestoreFailure()
    data object PassphraseTooShort : RestoreFailure()

    /** The repository rejected the bundle with a user-facing [message] (e.g. an identity already exists). */
    data class Rejected(val message: String) : RestoreFailure()
    data object Unknown : RestoreFailure()
}

@HiltViewModel
class CreateIdentityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generateIdentityUseCase: GenerateIdentityUseCase,
    private val getIdentityUseCase: GetIdentityUseCase,
    private val inspectBackupUseCase: InspectBackupUseCase,
    private val restoreIdentityBackupUseCase: RestoreIdentityBackupUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<CreateIdentityUiState>(CreateIdentityUiState.Intro)
    val uiState: StateFlow<CreateIdentityUiState> = _uiState.asStateFlow()

    /** Encrypted bundle bytes kept between inspection and restore; not secret (ciphertext only). */
    private var pendingBundle: ByteArray? = null

    fun onIntroContinue() {
        _uiState.value = CreateIdentityUiState.NameEntry()
    }

    fun onDisplayNameChange(name: String) {
        val current = _uiState.value
        if (current is CreateIdentityUiState.NameEntry) {
            _uiState.value = current.copy(displayName = name, error = null)
        }
    }

    fun createIdentity() {
        val current = _uiState.value
        if (current !is CreateIdentityUiState.NameEntry) return
        val trimmed = current.displayName.trim()
        if (trimmed.length !in DISPLAY_NAME_MIN..DISPLAY_NAME_MAX) {
            _uiState.value = current.copy(error = "نام باید بین $DISPLAY_NAME_MIN تا $DISPLAY_NAME_MAX کاراکتر باشد")
            return
        }
        if (_uiState.value is CreateIdentityUiState.Creating) return
        viewModelScope.launch {
            _uiState.value = CreateIdentityUiState.Creating
            when (val result = generateIdentityUseCase(trimmed)) {
                is AppResult.Success -> _uiState.value = CreateIdentityUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = CreateIdentityUiState.Error(result.error.message)
            }
        }
    }

    fun retryFromError() {
        _uiState.value = CreateIdentityUiState.NameEntry()
    }

    /** Reads the document at [uri] and inspects its header; moves to [CreateIdentityUiState.RestoreConfirm]. */
    fun onBackupFileSelected(uri: Uri) {
        if (_uiState.value !is CreateIdentityUiState.Intro) return
        _uiState.value = CreateIdentityUiState.InspectingBackup
        viewModelScope.launch {
            val bytes = readDocument(context, uri)
            if (bytes == null) {
                _uiState.value = CreateIdentityUiState.RestoreFailed(RestoreFailure.UnreadableFile)
                return@launch
            }
            _uiState.value = when (val result = inspectBackupUseCase(bytes)) {
                is AppResult.Success -> {
                    pendingBundle = bytes
                    CreateIdentityUiState.RestoreConfirm(header = result.data)
                }
                is AppResult.Error -> CreateIdentityUiState.RestoreFailed(result.error.toInspectFailure())
            }
        }
    }

    fun onRestorePassphraseChange(passphrase: String) {
        val current = _uiState.value
        if (current is CreateIdentityUiState.RestoreConfirm) {
            _uiState.value = current.copy(passphrase = passphrase, failure = null)
        }
    }

    fun restoreBackup() {
        val current = _uiState.value
        val bundle = pendingBundle
        if (current !is CreateIdentityUiState.RestoreConfirm || bundle == null) return
        if (current.passphrase.length < PASSPHRASE_MIN_CHARS) {
            _uiState.value = current.copy(failure = RestoreFailure.PassphraseTooShort)
            return
        }
        _uiState.value = CreateIdentityUiState.Restoring
        viewModelScope.launch {
            val passphrase = current.passphrase.toCharArray()
            val result = try {
                restoreIdentityBackupUseCase(bundle, passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
            _uiState.value = when (result) {
                is AppResult.Success -> {
                    pendingBundle = null
                    getIdentityUseCase()?.let { CreateIdentityUiState.Success(it, restored = result.data) }
                        ?: CreateIdentityUiState.RestoreFailed(RestoreFailure.Unknown)
                }
                is AppResult.Error -> current.copy(passphrase = "", failure = result.error.toRestoreFailure())
            }
        }
    }

    /** Abandons the restore flow and returns to the intro step. */
    fun cancelRestore() {
        pendingBundle = null
        _uiState.value = CreateIdentityUiState.Intro
    }

    companion object {
        const val DISPLAY_NAME_MIN = 2
        const val DISPLAY_NAME_MAX = 32
        const val PASSPHRASE_MIN_CHARS = 8
    }
}

/**
 * Largest document accepted as a backup. A real bundle is a few MiB at most; the cap keeps a
 * mis-picked or hostile file from exhausting the heap (the bundle, its ciphertext slice and the
 * plaintext are all held at once during decode).
 */
private const val MAX_BUNDLE_BYTES = 32 * 1024 * 1024
private const val READ_CHUNK_BYTES = 64 * 1024

/** Reads the document at [uri]; null when it cannot be opened or exceeds [MAX_BUNDLE_BYTES]. */
private suspend fun readDocument(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val declaredSize = queryDocumentSize(resolver, uri)
        if (declaredSize != null && declaredSize > MAX_BUNDLE_BYTES) return@runCatching null
        resolver.openInputStream(uri)?.use { input -> readBounded(input, declaredSize?.toInt()) }
    }.getOrNull()
}

private fun queryDocumentSize(resolver: ContentResolver, uri: Uri): Long? =
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
    }

/** Reads [input] to the end, aborting (null) as soon as more than [MAX_BUNDLE_BYTES] arrive. */
private fun readBounded(input: InputStream, sizeHint: Int?): ByteArray? {
    val output = ByteArrayOutputStream(sizeHint?.coerceIn(0, MAX_BUNDLE_BYTES) ?: READ_CHUNK_BYTES)
    val chunk = ByteArray(READ_CHUNK_BYTES)
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        if (output.size() + read > MAX_BUNDLE_BYTES) return null
        output.write(chunk, 0, read)
    }
    return output.toByteArray()
}

/** Errors from inspecting the header: the bundle is malformed or the file is not a backup at all. */
private fun AppError.toInspectFailure(): RestoreFailure = when (this) {
    is AppError.Validation -> RestoreFailure.Rejected(message)
    else -> RestoreFailure.UnreadableFile
}

/**
 * Errors from decrypting/importing. A wrong passphrase and a tampered bundle are
 * indistinguishable (AEAD failure), so both surface as [RestoreFailure.WrongPassphrase].
 */
private fun AppError.toRestoreFailure(): RestoreFailure = when (this) {
    is AppError.Security, is AppError.Crypto -> RestoreFailure.WrongPassphrase
    is AppError.Validation -> RestoreFailure.Rejected(message)
    is AppError.NotFound, is AppError.Network, is AppError.Unknown, is AppError.ProtocolVersion ->
        RestoreFailure.Unknown
}

sealed class IdentityUiState {
    data object Loading : IdentityUiState()
    data class Loaded(val identity: Identity) : IdentityUiState()
    data object None : IdentityUiState()
}

@HiltViewModel
class IdentityViewModel @Inject constructor(
    private val getIdentityUseCase: GetIdentityUseCase,
    private val updateDisplayNameUseCase: UpdateDisplayNameUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<IdentityUiState>(IdentityUiState.Loading)
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = getIdentityUseCase()
            _uiState.value = if (identity != null) {
                IdentityUiState.Loaded(identity)
            } else {
                IdentityUiState.None
            }
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            when (updateDisplayNameUseCase(name)) {
                is AppResult.Success -> {
                    val identity = getIdentityUseCase()
                    if (identity != null) {
                        _uiState.value = IdentityUiState.Loaded(identity)
                    }
                }
                is AppResult.Error -> Unit
            }
        }
    }
}
