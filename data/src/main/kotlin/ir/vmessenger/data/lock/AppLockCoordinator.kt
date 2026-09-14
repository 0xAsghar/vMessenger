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
import ir.vmessenger.domain.usecase.settings.SecureWipeUseCase
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

/**
 * How many wrong PINs erase the app, and when to start saying so.
 *
 * Here rather than in the UI because the screen counting down and the code doing the erasing have
 * to agree: a countdown to a threshold the eraser does not share is worse than no countdown.
 */
object AppLockWipePolicy {
    const val MAX_FAILED_ATTEMPTS = 10
    const val WARN_AT_REMAINING = 3
}

sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data object NoLockSet : UnlockResult
    data object HardwareRefused : UnlockResult

    /** Enough wrong PINs that the app erased itself; there is nothing left to unlock. */
    data object Wiped : UnlockResult

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
    private val secureWipe: SecureWipeUseCase,
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
            !pinVerifier.matches(pin, verifier) -> wrongPin(attempt)
            // The hardware refused: authenticated to us, but not recently enough for the Keystore.
            // The PIN was *right*, so the attempt is given back — otherwise a biometric
            // re-enrolment could march someone toward erasing their data through no fault of
            // their own, which is the opposite of what the counter is for.
            _state.value == LockState.LockedStrict && !restoreStrictPassphrase() -> {
                lockPreferences.clearAttempts()
                UnlockResult.HardwareRefused
            }
            else -> {
                markUnlocked()
                UnlockResult.Unlocked
            }
        }
    }

    /**
     * Unlocks a *soft* lock on a biometric result the screen already verified.
     *
     * Refused in strict mode on purpose: there the passphrase comes back out of a Keystore key,
     * and only a real authentication against that key can produce it. Letting a biometric bypass
     * the PIN there would unlock the screen over a database that is still shut.
     */
    suspend fun unlockWithBiometric(): Boolean {
        if (_state.value != LockState.Locked) return false
        lockPreferences.clearAttempts()
        markUnlocked()
        return true
    }

    private fun markUnlocked() {
        databaseKeyProvider.unlock()
        _state.value = LockState.Unlocked
    }

    /**
     * Counts a wrong PIN, and erases the app once the user has opted into that and run out.
     *
     * The wipe never returns — [SecureWipeUseCase] tears the process down — so the result it
     * reports is for the case where it somehow does.
     */
    private suspend fun wrongPin(attempt: Int): UnlockResult {
        val armed = privacyPreferences.wipeOnFailedAttempts.first()
        if (!armed || attempt < AppLockWipePolicy.MAX_FAILED_ATTEMPTS) return UnlockResult.Wrong(attempt)
        AppLogger.warn(TAG, "failed attempt limit reached; wiping")
        secureWipe()
        return UnlockResult.Wiped
    }

    /** Turns the screen gate on. Strict mode is a separate, deliberate step. */
    suspend fun setPin(pin: CharArray) {
        val created = pinVerifier.create(pin)
        lockPreferences.setVerifier(PinVerifierBlob(created.salt, created.nonce, created.sealed))
        lockPreferences.clearAttempts()
        privacyPreferences.setAppLockEnabled(true)
    }

    /**
     * Removes the lock entirely.
     *
     * Refuses while strict mode still holds the key, because clearing the lock would otherwise
     * delete the only way to open the database. The caller turns strict mode off first.
     */
    suspend fun clearLock(): Boolean {
        if (privacyPreferences.strictLockEnabled.first() && !disableStrictMode()) return false
        lockPreferences.clear()
        privacyPreferences.setAppLockEnabled(false)
        _state.value = LockState.Unlocked
        return true
    }

    /**
     * Moves the database passphrase behind the auth-bound key.
     *
     * Write the new copy, then remove the old one — in that order, so a crash between the two
     * leaves an install that still opens rather than one that opens with neither key.
     *
     * Removing the ordinary copy is the point, and it is what separates this from security theatre:
     * leaving it in place would mean anything that can read the app's files still has the key, and
     * the hardware gate would protect nothing. The cost is that the Keystore becomes the only way
     * back in, which is why enabling this is gated on a completed backup in the UI.
     */
    suspend fun enableStrictMode(validitySeconds: Int): Boolean {
        val passphrase = databaseKeyProvider.getPassphraseOrNull()
        if (!strictKeys.isSupported || passphrase == null) return false
        strictKeys.createKey(validitySeconds)
        lockPreferences.setStrictWrappedPassphrase(strictKeys.wrap(passphrase))
        securityPreferences.clearWrappedDbPassphrase()
        privacyPreferences.setStrictLockEnabled(true)
        return true
    }

    /**
     * Puts the passphrase back under the ordinary key.
     *
     * Only possible while unlocked, because that is the only time the passphrase is in memory —
     * and the ordinary copy has to be written *before* the strict one is dropped, or turning the
     * setting off would lock the user out of their own database.
     */
    suspend fun disableStrictMode(): Boolean {
        val passphrase = databaseKeyProvider.getPassphraseOrNull() ?: return false
        securityPreferences.setWrappedDbPassphrase(
            keyStoreKeyManager.wrap(KeyStoreKeyManager.ALIAS_DATABASE, passphrase),
        )
        lockPreferences.clearStrictPassphrase()
        strictKeys.deleteKey()
        privacyPreferences.setStrictLockEnabled(false)
        databaseKeyProvider.unlock()
        return true
    }

    /** Puts the passphrase back in memory after an authentication. */
    private suspend fun restoreStrictPassphrase(): Boolean {
        val passphrase = lockPreferences.getStrictWrappedPassphrase()?.let(strictKeys::unwrapOrNull) ?: return false
        // Into memory only. Writing an ordinary wrapped copy here would undo strict mode on the
        // first unlock and leave it undone for good.
        databaseKeyProvider.provide(passphrase)
        return true
    }

    private companion object {
        const val TAG = "AppLock"
    }
}
