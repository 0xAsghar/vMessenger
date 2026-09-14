package ir.vmessenger.core.datastore

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val PIN_SALT = stringPreferencesKey("app_lock_salt")
private val PIN_NONCE = stringPreferencesKey("app_lock_nonce")
private val PIN_VERIFIER = stringPreferencesKey("app_lock_verifier")
private val STRICT_WRAPPED_DB = stringPreferencesKey("app_lock_strict_db")
private val ATTEMPTS = intPreferencesKey("app_lock_attempts")

/** The PIN verifier, the strict-mode passphrase copy, and the failed-attempt count. */
data class PinVerifierBlob(val salt: ByteArray, val nonce: ByteArray, val sealed: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PinVerifierBlob &&
            salt.contentEquals(other.salt) &&
            nonce.contentEquals(other.nonce) &&
            sealed.contentEquals(other.sealed)

    override fun hashCode(): Int =
        31 * (31 * salt.contentHashCode() + nonce.contentHashCode()) + sealed.contentHashCode()
}

/**
 * App-lock state, in the same store as the wrapped database passphrase.
 *
 * Same store so a secure wipe takes it with everything else, but its own class so that clearing the
 * lock is a separate operation from clearing the store — a "forget my PIN" that took the database
 * passphrase with it would destroy the install rather than unlock it.
 */
@Singleton
class AppLockPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun getVerifier(): PinVerifierBlob? {
        val stored = context.securityDataStore.data.first()
        val salt = stored[PIN_SALT]?.let(::decode)
        val nonce = stored[PIN_NONCE]?.let(::decode)
        val sealed = stored[PIN_VERIFIER]?.let(::decode)
        return if (salt == null || nonce == null || sealed == null) null else PinVerifierBlob(salt, nonce, sealed)
    }

    suspend fun setVerifier(blob: PinVerifierBlob) {
        context.securityDataStore.edit {
            it[PIN_SALT] = encode(blob.salt)
            it[PIN_NONCE] = encode(blob.nonce)
            it[PIN_VERIFIER] = encode(blob.sealed)
        }
    }

    /** The database passphrase re-wrapped under the auth-bound key; present only in strict mode. */
    suspend fun getStrictWrappedPassphrase(): ByteArray? =
        context.securityDataStore.data.first()[STRICT_WRAPPED_DB]?.let(::decode)

    suspend fun setStrictWrappedPassphrase(wrapped: ByteArray) {
        context.securityDataStore.edit { it[STRICT_WRAPPED_DB] = encode(wrapped) }
    }

    suspend fun clearStrictPassphrase() {
        context.securityDataStore.edit { it.remove(STRICT_WRAPPED_DB) }
    }

    suspend fun failedAttempts(): Int = context.securityDataStore.data.first()[ATTEMPTS] ?: 0

    /**
     * Records an attempt *before* it is checked, and returns the new count.
     *
     * Write-ahead on purpose: incrementing only after a failed check lets someone force-stop the
     * app between the guess and the write and go on guessing with the counter never moving.
     */
    suspend fun recordAttempt(): Int {
        var next = 0
        context.securityDataStore.edit {
            next = (it[ATTEMPTS] ?: 0) + 1
            it[ATTEMPTS] = next
        }
        return next
    }

    suspend fun clearAttempts() {
        context.securityDataStore.edit { it.remove(ATTEMPTS) }
    }

    /** Removes the lock and leaves everything else in the store alone. */
    suspend fun clear() {
        context.securityDataStore.edit {
            it.remove(PIN_SALT)
            it.remove(PIN_NONCE)
            it.remove(PIN_VERIFIER)
            it.remove(STRICT_WRAPPED_DB)
            it.remove(ATTEMPTS)
        }
    }
}

private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
