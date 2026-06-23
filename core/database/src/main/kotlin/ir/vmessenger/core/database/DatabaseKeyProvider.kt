package ir.vmessenger.core.database

import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.datastore.SecurityPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyProvider @Inject constructor(
    private val keyStoreKeyManager: KeyStoreKeyManager,
    private val securityPreferences: SecurityPreferences,
) {
    private var cachedPassphrase: ByteArray? = null

    suspend fun initialize() {
        if (cachedPassphrase != null) return
        val wrapped = securityPreferences.getWrappedDbPassphrase()
        cachedPassphrase = if (wrapped != null) {
            keyStoreKeyManager.unwrap(wrapped)
        } else {
            val passphrase = keyStoreKeyManager.getOrCreateDatabasePassphrase(null)
            securityPreferences.setWrappedDbPassphrase(keyStoreKeyManager.wrap(passphrase))
            passphrase
        }
    }

    fun getPassphrase(): ByteArray =
        cachedPassphrase ?: error("DatabaseKeyProvider.initialize() must be called before opening the database")
}
