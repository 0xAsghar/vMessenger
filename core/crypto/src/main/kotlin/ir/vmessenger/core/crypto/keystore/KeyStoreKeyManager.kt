package ir.vmessenger.core.crypto.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ir.vmessenger.core.crypto.CryptoEngine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyStoreKeyManager @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun getOrCreateDatabasePassphrase(wrappedPassphrase: ByteArray?): ByteArray {
        if (wrappedPassphrase != null && wrappedPassphrase.isNotEmpty()) {
            return unwrap(wrappedPassphrase)
        }
        val passphrase = cryptoEngine.randomBytes(DATABASE_KEY_BYTES)
        return passphrase
    }

    fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun unwrap(wrapped: ByteArray): ByteArray {
        val iv = wrapped.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = wrapped.copyOfRange(GCM_IV_LENGTH, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun wrapPrivateKey(@Suppress("UNUSED_PARAMETER") alias: String, privateKey: ByteArray): ByteArray = wrap(privateKey)

    fun unwrapPrivateKey(@Suppress("UNUSED_PARAMETER") alias: String, wrapped: ByteArray): ByteArray = unwrap(wrapped)

    fun deleteMasterKey() {
        if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val existing = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "vmessenger_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        const val DATABASE_KEY_BYTES = 32
    }
}
