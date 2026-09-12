package ir.vmessenger.core.crypto.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps and unwraps the app's long-lived secrets (SQLCipher passphrase,
 * attachment master key, identity private keys) with a non-exportable AES-GCM
 * key held by the Android Keystore, StrongBox-backed where the device has one.
 *
 * Every blob is `0x02 ‖ iv ‖ ct` ([WrappedKeyBlob]) and is authenticated with
 * AAD derived from its alias, so a blob wrapped for one purpose cannot be
 * unwrapped as another.
 *
 * **Deliberate trade-off:** the master key is created *without*
 * `setUserAuthenticationRequired` and *without* `setUnlockedDeviceRequired`.
 * The network foreground service must open the encrypted database to receive
 * messages while the screen is locked; requiring an unlocked device would stop
 * delivery whenever the phone is in a pocket. At-rest protection therefore
 * rests on the Keystore (and StrongBox) rather than on the lock state.
 */
@Singleton
class KeyStoreKeyManager @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** A fresh random SQLCipher passphrase; the caller wraps and persists it. */
    fun newDatabasePassphrase(): ByteArray = cryptoEngine.randomBytes(DATABASE_KEY_BYTES)

    fun wrap(alias: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        cipher.updateAAD(WrappedKeyBlob.aad(alias))
        return WrappedKeyBlob.encode(cipher.iv, cipher.doFinal(plaintext))
    }

    /**
     * @throws IllegalArgumentException when [wrapped] is not a current blob
     * (an unversioned 0.x blob is rejected rather than guessed at).
     */
    fun unwrap(alias: String, wrapped: ByteArray): ByteArray {
        val parsed = WrappedKeyBlob.decode(wrapped)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, parsed.iv))
        cipher.updateAAD(WrappedKeyBlob.aad(alias))
        return cipher.doFinal(parsed.ciphertext)
    }

    fun wrapPrivateKey(alias: String, privateKey: ByteArray): ByteArray = wrap(alias, privateKey)

    fun unwrapPrivateKey(alias: String, wrapped: ByteArray): ByteArray = unwrap(alias, wrapped)

    /** Destroys the master key: every wrapped blob on the device becomes undecryptable (secure wipe). */
    fun deleteMasterKey() {
        if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val existing = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val strongBoxCapable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        return runCatching { generateMasterKey(strongBox = strongBoxCapable) }
            .getOrElse { error ->
                // Many devices advertise the API without the hardware behind it,
                // and they do not all report it as StrongBoxUnavailableException:
                // a plain ProviderException or KeyStoreException out of
                // generateKey() is just as common. Any StrongBox failure falls
                // back to the TEE-backed key — refusing to create one at all
                // would leave the app permanently unusable on that device.
                if (!strongBoxCapable) throw error
                AppLogger.warn(TAG, "StrongBox key generation failed, falling back to TEE: $error")
                runCatching { keyStore.deleteEntry(MASTER_KEY_ALIAS) }
                generateMasterKey(strongBox = false)
            }
    }

    private fun generateMasterKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(MASTER_KEY_BITS)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "Crypto"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "vmessenger_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val MASTER_KEY_BITS = 256
        const val DATABASE_KEY_BYTES = 32

        /** AAD alias of the SQLCipher passphrase blob. */
        const val ALIAS_DATABASE = "db"

        /** AAD alias of the attachment master-key blob. */
        const val ALIAS_ATTACHMENTS = "attachments"
    }
}
