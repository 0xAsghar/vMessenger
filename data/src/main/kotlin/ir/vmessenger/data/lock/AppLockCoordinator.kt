package ir.vmessenger.data.lock

import android.os.SystemClock
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
    /**
     * The starting value, before the preferences have been read.
     *
     * It exists because the alternative is a race the app cannot win: reading whether a lock is
     * set is suspending, so for the first frames the answer is genuinely unknown, and starting at
     * [Unlocked] meant the app composed its real content in that window — which under strict mode
     * builds a DAO, opens the database, and crashes before the lock can be drawn. Everything
     * treats "not [Unlocked]" as locked, so this fails closed by construction; the system splash
     * covers it, and nothing is drawn until it resolves.
     */
    Undetermined,

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

    /**
     * Wrong PINs tolerated at full speed before the wait starts, and how it grows.
     *
     * Argon2id already costs half a second to two seconds a guess, which is a real rate limit —
     * a four-digit keyspace is hours rather than seconds. It is not a *bound*, though, and the
     * wipe that would bound it is opt-in and off by default, so without this the shipped default
     * left an unattended phone to be walked through ten thousand guesses. The wait doubles and
     * caps, so a user who has genuinely forgotten which of their PINs this is loses seconds, and
     * someone working through the keyspace loses the keyspace.
     */
    const val BACKOFF_AFTER_ATTEMPTS = 4
    const val BACKOFF_BASE_MS = 5_000L
    const val BACKOFF_MAX_MS = 5 * 60_000L

    /** The wait owed after [attempts] consecutive wrong PINs. */
    fun backoffMs(attempts: Int): Long {
        if (attempts < BACKOFF_AFTER_ATTEMPTS) return 0L
        val doublings = (attempts - BACKOFF_AFTER_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
        return (BACKOFF_BASE_MS shl doublings).coerceAtMost(BACKOFF_MAX_MS)
    }

    private const val MAX_DOUBLINGS = 16
}

sealed interface UnlockResult {
    data object Unlocked : UnlockResult

    /** Too many wrong PINs too quickly; [waitMs] is how long is left before the next try. */
    data class TooSoon(val waitMs: Long) : UnlockResult
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
    // Provider for the same reason as [database] above: the wipe coordinator holds the database
    // directly, so injecting it eagerly opens the database at construction — which is exactly what
    // strict mode forbids, and this class is built while the app is still locked.
    private val secureWipe: Provider<SecureWipeUseCase>,
) {
    /** Monotonic stamp of the last wrong PIN; null until one is entered in this process. */
    @Volatile
    private var lastFailureElapsedMs: Long? = null

    private val _state = MutableStateFlow(LockState.Undetermined)
    val state: StateFlow<LockState> = _state.asStateFlow()

    val strictModeSupported: Boolean get() = strictKeys.isSupported

    val wipeOnFailedAttempts: Flow<Boolean> = privacyPreferences.wipeOnFailedAttempts

    /** Called at startup and whenever the auto-lock timeout expires in the background. */
    suspend fun lockIfEnabled() {
        if (!privacyPreferences.appLockEnabled.first()) {
            _state.value = LockState.Unlocked
            return
        }
        completeInterruptedEnable()
        val strict = privacyPreferences.strictLockEnabled.first() && strictKeys.hasKey()
        if (strict) {
            // Order matters: the open connection holds the key, so it goes first — but only when
            // there is something to close. On a cold start under strict mode the passphrase was
            // never in memory, and asking Hilt for the database purely to close it *builds* it,
            // which asks for the passphrase, which throws. runCatching caught that, so it was
            // only ever noise in the log — but it was Keystore and DataStore work on the main
            // thread to reach a conclusion already known.
            if (databaseKeyProvider.getPassphraseOrNull() != null) {
                runCatching { database.get().close() }
                    .onFailure { AppLogger.warn(TAG, "closing the database to lock failed: ${it.message}") }
            }
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
        val blob = lockPreferences.getVerifier() ?: return noVerifierStored()
        val owed = backoffRemainingMs()
        val attempt = if (owed == 0L) lockPreferences.recordAttempt() else 0
        val verifier = PinVerifier.Verifier(blob.salt, blob.nonce, blob.sealed)
        return when {
            // Checked before the PIN is, and before the attempt is recorded: a guess that is not
            // allowed yet must not cost the guesser an attempt, or holding the key down would
            // drive the count to the wipe threshold without ever testing a PIN.
            owed > 0L -> UnlockResult.TooSoon(owed)
            !pinVerifier.matches(pin, verifier) -> {
                lastFailureElapsedMs = SystemClock.elapsedRealtime()
                wrongPin(attempt)
            }
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
        secureWipe.get()()
        return UnlockResult.Wiped
    }

    /**
     * Finishes an [enableStrictMode] that was interrupted between writing the flag and deleting
     * the ordinary copy.
     *
     * That interruption is deliberately survivable — see [enableStrictMode] — but what it leaves
     * behind is strict mode that protects nothing, because the non-authenticating copy of the
     * passphrase is still on disk. Removing it here is the second half of the same operation.
     */
    private suspend fun completeInterruptedEnable() {
        val halfDone = privacyPreferences.strictLockEnabled.first() &&
            strictKeys.hasKey() &&
            lockPreferences.getStrictWrappedPassphrase() != null &&
            securityPreferences.getWrappedDbPassphrase() != null
        if (halfDone) {
            AppLogger.warn(TAG, "strict mode was enabled without removing the ordinary key; removing it now")
            securityPreferences.clearWrappedDbPassphrase()
        }
    }

    /**
     * How long is still owed before the next guess is allowed, or zero.
     *
     * Measured on [SystemClock.elapsedRealtime] for the same reason the auto-lock is: a wait
     * measured against the wall clock is defeated by changing the device date. It is held in
     * memory rather than on disk, so a reboot forgives the wait currently being served — but not
     * the count it was derived from, so the next one is just as long. Rebooting between guesses
     * costs more than waiting.
     */
    private suspend fun backoffRemainingMs(): Long {
        val attempts = lockPreferences.failedAttempts()
        val owed = AppLockWipePolicy.backoffMs(attempts)
        val since = lastFailureElapsedMs ?: return 0L
        return (owed - (SystemClock.elapsedRealtime() - since)).coerceAtLeast(0L)
    }

    /**
     * The lock is switched on with no PIN stored, so open it and switch it off.
     *
     * Reachable: [clearLock] wipes the lock store and *then* clears the flag, so an interruption
     * between the two leaves exactly this. Refusing to open protects nothing — there is no secret
     * to check — and it strands the user behind a screen that can never accept anything, since the
     * only other way out is reinstalling and losing the database.
     *
     * It does not open when a strict blob is still there: the screen would come down onto a
     * database that has no key in memory. [clearLock] refuses to create that combination, so this
     * is the belt to its braces.
     */
    private suspend fun noVerifierStored(): UnlockResult {
        if (lockPreferences.getStrictWrappedPassphrase() != null) return UnlockResult.NoLockSet
        AppLogger.warn(TAG, "the app lock is on with no PIN stored; finishing the clear that was interrupted")
        privacyPreferences.setAppLockEnabled(false)
        _state.value = LockState.Unlocked
        return UnlockResult.NoLockSet
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
        val blocked = (privacyPreferences.strictLockEnabled.first() && !disableStrictMode()) ||
            strictBlobIsTheOnlyKey()
        if (blocked) return false
        lockPreferences.clear()
        privacyPreferences.setAppLockEnabled(false)
        _state.value = LockState.Unlocked
        return true
    }

    /**
     * True when the lock store holds the only wrapping of the database passphrase.
     *
     * [clearLock] wipes that store, so it has to ask the files rather than the flag: a strict blob
     * with no ordinary copy beside it is load-bearing whatever `strictLockEnabled` happens to say,
     * and clearing it would leave an encrypted database with no key anywhere.
     */
    private suspend fun strictBlobIsTheOnlyKey(): Boolean {
        val onlyKey = lockPreferences.getStrictWrappedPassphrase() != null &&
            securityPreferences.getWrappedDbPassphrase() == null
        if (onlyKey) AppLogger.warn(TAG, "refusing to clear the lock: the strict key is the only way in")
        return onlyKey
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
     * back in, which is why the UI puts a confirmation in front of this that names the loss and
     * says to take a backup first. It is a warning, not a gate: nothing records that a backup was
     * actually taken, so nothing here can check one.
     */
    suspend fun enableStrictMode(validitySeconds: Int): Boolean {
        val passphrase = databaseKeyProvider.getPassphraseOrNull()
        if (!strictKeys.isSupported || passphrase == null) return false
        strictKeys.createKey(validitySeconds)
        lockPreferences.setStrictWrappedPassphrase(strictKeys.wrap(passphrase))
        // The flag goes before the deletion, and that order is the whole safety property. The
        // other way round, a crash between the two left the ordinary copy gone and the flag still
        // false: nothing locked, so nothing ever authenticated, and `load()` then refused to mint
        // a replacement because a strict blob existed — an install that threw on every start over
        // an intact database. This way the interruption leaves both copies and a strict flag,
        // which opens, and [completeInterruptedEnable] tidies the leftover on the next start.
        privacyPreferences.setStrictLockEnabled(true)
        securityPreferences.clearWrappedDbPassphrase()
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
        // Flag first, then the blob. Dropping the blob while the flag was still true left the
        // lock asking for an authentication that could no longer unwrap anything, with the
        // ordinary copy sitting right there unused — the user shut out of their own database by
        // the act of turning the protection off.
        privacyPreferences.setStrictLockEnabled(false)
        lockPreferences.clearStrictPassphrase()
        strictKeys.deleteKey()
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
