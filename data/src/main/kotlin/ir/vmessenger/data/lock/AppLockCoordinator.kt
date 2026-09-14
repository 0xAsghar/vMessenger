package ir.vmessenger.data.lock

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.crypto.lock.PinVerifier
import ir.vmessenger.core.crypto.lock.StrictModeKeyManager
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.core.database.VMessengerDatabase
import ir.vmessenger.core.datastore.AppLockPreferences
import ir.vmessenger.core.datastore.PinVerifierBlob
import ir.vmessenger.core.datastore.PrivacyPreferences
import ir.vmessenger.core.datastore.SecurityPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Why the lock screen is up, or that it is not. */
enum class LockState {
    /** No PIN has been set, or the user has already authenticated. */
    Unlocked,

    /** Waiting for a PIN. Messages still arrive. */
    Locked,

    /** Waiting for a PIN *and* the database is shut, so nothing is being delivered. */
    LockedStrict,
}

sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data object NoLockSet : UnlockResult
    data object HardwareRefused : UnlockResult

    /** [attempt] is the running total, so the UI can warn before a wipe rather than after. */
    data class Wrong(val attempt: Int) : UnlockResult
}

/**
 * Owns whether the app is locked, and what that costs.
 *
 * Two honest modes, and the difference is not cosmetic. The default gate covers the screen: it
 * stops the person who picked your phone up, and it adds nothing at rest, because the ordinary
 * Keystore key is deliberately usable without authentication so the network service can decrypt
 * while the screen is off. Strict mode re-wraps the database passphrase under a second key the
 * hardware refuses to use until the user authenticates — which is what moves the rate limiting into
 * the TEE, where a short PIN is genuinely strong — and pays for it by stopping delivery while
 * locked.
 *
 * The Room database is injected through a [Provider] because locking has to *close* it: SQLCipher
 * holds the key inside its open connection, so forgetting the cached passphrase while a handle is
 * still open protects nothing.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // one collaborator and one step per layer
class AppLockCoordinator @Inject constructor(
    private val privacyPreferences: PrivacyPreferences,
    private val lockPreferences: AppLockPreferences,
    private val securityPreferences: SecurityPreferences,
    private val pinVerifier: PinVerifier,
    private val strictKeys: StrictModeKeyManager,
    private val keyStoreKeyManager: KeyStoreKeyManager,
    private val databaseKeyProvider: DatabaseKeyProvider,
    private val database: Provider<VMessengerDatabase>,
) {
    private val _state = MutableStateFlow(LockState.Unlocked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    val strictModeSupported: Boolean get() = strictKeys.isSupported

    val wipeOnFailedAttempts: Flow<Boolean> = privacyPreferences.wipeOnFailedAttempts

    /** Called at startup and whenever the auto-lock timeout expires in the background. */
    suspend fun lockIfEnabled() {
        if (!privacyPreferences.appLockEnabled.first()) {
            _state.value = LockState.Unlocked
            return
        }
        val strict = privacyPreferences.strictLockEnabled.first() && strictKeys.hasKey()
        if (strict) {
            // Order matters: the open connection holds the key, so it goes first.
            runCatching { database.get().close() }
                .onFailure { AppLogger.warn(TAG, "closing the database to lock failed: ${it.message}") }
            databaseKeyProvider.lock()
        }
        _state.value = if (strict) LockState.LockedStrict else LockState.Locked
    }

    /**
     * Checks [pin] and, in strict mode, restores the passphrase with it.
     *
     * The attempt is recorded before it is checked — see [AppLockPreferences.recordAttempt] — so a
     * force-stop between the guess and the write cannot reset the count.
     */
    suspend fun unlock(pin: CharArray): UnlockResult {
        val blob = lockPreferences.getVerifier() ?: return UnlockResult.NoLockSet
        val attempt = lockPreferences.recordAttempt()
        val verifier = PinVerifier.Verifier(blob.salt, blob.nonce, blob.sealed)
        return when {
            !pinVerifier.matches(pin, verifier) -> UnlockResult.Wrong(attempt)
            // The hardware refused: authenticated to us, but not recently enough for the Keystore.
            _state.value == LockState.LockedStrict && !restoreStrictPassphrase() -> UnlockResult.HardwareRefused
            else -> {
                lockPreferences.clearAttempts()
                databaseKeyProvider.unlock()
                _state.value = LockState.Unlocked
                UnlockResult.Unlocked
            }
        }
    }

    /** Turns the screen gate on. Strict mode is a separate, deliberate step. */
    suspend fun setPin(pin: CharArray) {
        val created = pinVerifier.create(pin)
        lockPreferences.setVerifier(PinVerifierBlob(created.salt, created.nonce, created.sealed))
        lockPreferences.clearAttempts()
        privacyPreferences.setAppLockEnabled(true)
    }

    /** Removes the lock entirely, including the strict-mode key and its copy of the passphrase. */
    suspend fun clearLock() {
        disableStrictMode()
        lockPreferences.clear()
        privacyPreferences.setAppLockEnabled(false)
        _state.value = LockState.Unlocked
    }

    /** Re-wraps the database passphrase under the auth-bound key. */
    suspend fun enableStrictMode(validitySeconds: Int): Boolean {
        val passphrase = databaseKeyProvider.getPassphraseOrNull()
        if (!strictKeys.isSupported || passphrase == null) return false
        strictKeys.createKey(validitySeconds)
        lockPreferences.setStrictWrappedPassphrase(strictKeys.wrap(passphrase))
        privacyPreferences.setStrictLockEnabled(true)
        return true
    }

    suspend fun disableStrictMode() {
        privacyPreferences.setStrictLockEnabled(false)
        strictKeys.deleteKey()
        databaseKeyProvider.unlock()
    }

    /**
     * Puts the passphrase back after an authentication.
     *
     * The non-auth copy is deliberately kept rather than deleted. Deleting it would mean a
     * biometric re-enrolment, a Keystore reset or a failed migration leaves the database
     * permanently unopenable. That is a real reduction in what strict mode guarantees, and it is
     * the trade this app makes knowingly: losing every message is not a way of protecting them.
     */
    private suspend fun restoreStrictPassphrase(): Boolean {
        val passphrase = lockPreferences.getStrictWrappedPassphrase()?.let(strictKeys::unwrapOrNull) ?: return false
        val rewrapped = keyStoreKeyManager.wrap(KeyStoreKeyManager.ALIAS_DATABASE, passphrase)
        securityPreferences.setWrappedDbPassphrase(rewrapped)
        return true
    }

    private companion object {
        const val TAG = "AppLock"
    }
}
