package ir.vmessenger.core.crypto.lock

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.keystore.WrappedKeyBlob
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The second Keystore key: the one the hardware will not use until the user proves who they are.
 *
 * This is what makes strict mode mean anything. The ordinary master key is deliberately created
 * *without* `setUserAuthenticationRequired`, so the network service can open the database while the
 * screen is locked — which also means a PIN checked in software adds nothing at rest. Re-wrapping
 * the database passphrase under a key the TEE or StrongBox refuses to use without a fresh
 * authentication moves the *key* behind the hardware, where the device's own credential checking
 * applies rather than a constant-factor KDF over a four-digit secret.
 *
 * Be precise about what that does and does not rate-limit. The hardware gates use of the key and
 * applies its own throttling to device-credential attempts. It does **not** check this app's PIN —
 * that is Argon2id in software, with the app's own backoff on top — so what strict mode buys is
 * that the database key cannot be produced at all without a device authentication, not that the
 * app's PIN is hardware-enforced.
 *
 * Two deliberate choices, both of which can cost a user their data if got wrong:
 *
 * - The alias is its own. It must never be [KeyStoreKeyManager]'s, whose StrongBox fallback path
 *   *deletes* its alias and regenerates — which would silently destroy this one.
 * - Authentication accepts the device credential as well as a strong biometric. That is what makes
 *   the key survive a new fingerprint enrolment: `setInvalidatedByBiometricEnrollment` only bites a
 *   key that can be opened by biometrics alone, and with `AUTH_DEVICE_CREDENTIAL` in the allowed
 *   set this one cannot be. What does destroy it is removing the device screen lock, which deletes
 *   every authentication-bound key, or moving the app's data to another device, since Keystore keys
 *   never travel. Both are unopenable-database territory, which is why turning strict mode on puts
 *   a confirmation in front of the user telling them to take a backup first.
 */
@Singleton
class StrictModeKeyManager @Inject constructor() {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasKey(): Boolean = keyStore.containsAlias(STRICT_KEY_ALIAS)

    /**
     * Creates the auth-bound key, replacing any previous one.
     *
     * [validitySeconds] is how long one authentication is honoured for; within that window the
     * service can reopen the database without prompting again, which is what lets a short auto-lock
     * timeout not mean a prompt on every database access.
     */
    fun createKey(validitySeconds: Int) {
        deleteKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            STRICT_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                validitySeconds,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        }
        generator.init(builder.build())
        generator.generateKey()
    }

    fun deleteKey() {
        if (keyStore.containsAlias(STRICT_KEY_ALIAS)) keyStore.deleteEntry(STRICT_KEY_ALIAS)
    }

    fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(WrappedKeyBlob.aad(ALIAS_STRICT_DATABASE))
        return WrappedKeyBlob.encode(cipher.iv, cipher.doFinal(plaintext))
    }

    /**
     * Unwraps, or returns null when the hardware refuses.
     *
     * Null is the ordinary case, not an error: it is what "the user has not authenticated recently
     * enough" looks like from here, and every background caller has to treat it as "stay locked"
     * rather than as a failure to report.
     */
    fun unwrapOrNull(wrapped: ByteArray): ByteArray? = runCatching {
        val parsed = WrappedKeyBlob.decode(wrapped)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, parsed.iv))
        cipher.updateAAD(WrappedKeyBlob.aad(ALIAS_STRICT_DATABASE))
        cipher.doFinal(parsed.ciphertext)
    }.getOrElse { error ->
        if (error is KeyPermanentlyInvalidatedException) {
            // Biometric enrolment changed and there is no way back to this key. The device
            // credential normally prevents this; if it happens the install needs a restore.
            AppLogger.error(TAG, "strict-mode key permanently invalidated")
        }
        null
    }

    private fun key(): SecretKey =
        (keyStore.getEntry(STRICT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: error("strict mode is on but its key is missing")

    private companion object {
        const val TAG = "Crypto"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val STRICT_KEY_ALIAS = "vmessenger_app_lock"
        const val ALIAS_STRICT_DATABASE = "db-strict"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
