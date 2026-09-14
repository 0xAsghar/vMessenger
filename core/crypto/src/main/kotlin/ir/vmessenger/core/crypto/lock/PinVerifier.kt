package ir.vmessenger.core.crypto.lock

import ir.vmessenger.core.crypto.CryptoEngine
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks an app-lock PIN without ever storing it.
 *
 * The PIN is stretched with Argon2id — the same primitive and the same parameters the encrypted
 * backup already uses — and the result is checked by *opening an AEAD box over a known constant*
 * rather than by comparing a stored hash. That gives authentication rather than equality: there is
 * nothing on disk that a correct guess has to merely match.
 *
 * What this buys, stated plainly so the UI copy does not overclaim it: against someone holding the
 * phone and guessing, the cost per attempt plus the rate limiting above it. Against someone who
 * has the salt and the verifier and can guess offline, a four-to-six digit PIN is 10⁴–10⁶
 * candidates and Argon2id is a constant factor, not a defence. That is why the *strict* mode does
 * not derive anything from this: it puts the database key behind a Keystore key the hardware
 * rate-limits, where a short secret is genuinely strong. See [ir.vmessenger.core.crypto.lock.StrictModeKeyManager].
 */
@Singleton
class PinVerifier @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    /** Salt, nonce and the sealed constant, to be persisted together. */
    data class Verifier(val salt: ByteArray, val nonce: ByteArray, val sealed: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Verifier &&
                salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) &&
                sealed.contentEquals(other.sealed)

        override fun hashCode(): Int =
            31 * (31 * salt.contentHashCode() + nonce.contentHashCode()) + sealed.contentHashCode()
    }

    fun create(pin: CharArray): Verifier {
        val salt = cryptoEngine.randomBytes(SALT_BYTES)
        val nonce = cryptoEngine.randomBytes(NONCE_BYTES)
        val key = derive(pin, salt)
        return try {
            Verifier(salt, nonce, cryptoEngine.xchacha20Poly1305Seal(key, nonce, WITNESS, AAD))
        } finally {
            cryptoEngine.memzero(key)
        }
    }

    /** True when [pin] reproduces the key that sealed the witness. Wrong PINs simply fail to open. */
    fun matches(pin: CharArray, verifier: Verifier): Boolean {
        val key = derive(pin, verifier.salt)
        return try {
            cryptoEngine.xchacha20Poly1305Open(key, verifier.nonce, verifier.sealed, AAD) != null
        } finally {
            cryptoEngine.memzero(key)
        }
    }

    /**
     * NFC-normalised before encoding, so a PIN typed with a different but equivalent representation
     * of the same characters still matches — the backup codec normalises for the same reason.
     */
    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val bytes = Normalizer.normalize(String(pin), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        return try {
            cryptoEngine.pwhashArgon2id(bytes, salt, OPS_LIMIT, MEM_LIMIT_BYTES, KEY_BYTES)
        } finally {
            cryptoEngine.memzero(bytes)
        }
    }

    companion object {
        /** The same cost the encrypted backup uses; roughly half a second to a couple on a phone. */
        const val OPS_LIMIT = 3L
        const val MEM_LIMIT_BYTES = 64L * 1024 * 1024
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 24
        const val KEY_BYTES = 32

        /** Four digits is the shortest a keypad should accept; anything less is decorative. */
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 32

        private val WITNESS = "vmessenger:app-lock:v1".toByteArray(Charsets.UTF_8)
        private val AAD = "vmessenger:app-lock".toByteArray(Charsets.UTF_8)
    }
}
