package ir.vmessenger.data.attachment

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.datastore.SecurityPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owner of the 32-byte attachment master key: generated once, wrapped by the
 * Android Keystore master key and stored as `wrapped_attachment_key` in
 * [SecurityPreferences]. It is deliberately separate from the SQLCipher
 * passphrase (different lifecycle; the DB key is never used elsewhere).
 * The unwrapped key is cached for the process lifetime; [reset] zeroizes it
 * (secure wipe).
 */
@Singleton
class AttachmentKeyProvider @Inject constructor(
    private val keyStoreKeyManager: KeyStoreKeyManager,
    private val securityPreferences: SecurityPreferences,
    private val cryptoEngine: CryptoEngine,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: ByteArray? = null

    /** The master key; callers must not mutate or retain the returned array. */
    suspend fun get(): ByteArray {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: load().also { cached = it }
        }
    }

    /** Forgets (and zeroizes) the cached key; the next [get] re-reads or re-creates it. */
    fun reset() {
        cached?.let(cryptoEngine::memzero)
        cached = null
    }

    private suspend fun load(): ByteArray {
        val wrapped = securityPreferences.getWrappedAttachmentKey()
        if (wrapped != null && wrapped.isNotEmpty()) {
            val key = keyStoreKeyManager.unwrap(KeyStoreKeyManager.ALIAS_ATTACHMENTS, wrapped)
            check(key.size == KEY_BYTES) { "attachment master key has unexpected size" }
            return key
        }
        val key = cryptoEngine.randomBytes(KEY_BYTES)
        securityPreferences.setWrappedAttachmentKey(
            keyStoreKeyManager.wrap(KeyStoreKeyManager.ALIAS_ATTACHMENTS, key),
        )
        AppLogger.info("Attachment", "attachment master key created")
        return key
    }

    companion object {
        const val KEY_BYTES = 32
    }
}
