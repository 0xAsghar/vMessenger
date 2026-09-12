package ir.vmessenger.core.database

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.datastore.SecurityPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the SQLCipher passphrase comes from: an existing Keystore-wrapped blob,
 * or a freshly generated one that is wrapped and persisted on first use.
 *
 * Separated from [DatabaseKeyProvider] so the caching/concurrency behaviour can
 * be exercised without the Android Keystore.
 */
interface DatabasePassphraseSource {
    suspend fun load(): ByteArray
}

@Singleton
class KeystoreDatabasePassphraseSource @Inject constructor(
    private val keyStoreKeyManager: KeyStoreKeyManager,
    private val securityPreferences: SecurityPreferences,
) : DatabasePassphraseSource {
    override suspend fun load(): ByteArray {
        val wrapped = securityPreferences.getWrappedDbPassphrase()
        if (wrapped != null && wrapped.isNotEmpty()) {
            return keyStoreKeyManager.unwrap(KeyStoreKeyManager.ALIAS_DATABASE, wrapped)
        }
        val passphrase = keyStoreKeyManager.newDatabasePassphrase()
        securityPreferences.setWrappedDbPassphrase(
            keyStoreKeyManager.wrap(KeyStoreKeyManager.ALIAS_DATABASE, passphrase),
        )
        return passphrase
    }
}

/**
 * Caches the unwrapped SQLCipher passphrase for the process lifetime.
 *
 * [initialize] is idempotent and safe to call concurrently: the first caller
 * unwraps (or creates) the passphrase under [mutex] while the others wait, so
 * a race between the application's async warm-up and the splash screen can
 * never create two passphrases — which would leave the database unopenable.
 */
@Singleton
class DatabaseKeyProvider @Inject constructor(
    private val source: DatabasePassphraseSource,
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedPassphrase: ByteArray? = null

    suspend fun initialize() {
        if (cachedPassphrase != null) return
        mutex.withLock {
            if (cachedPassphrase == null) cachedPassphrase = source.load()
        }
    }

    /**
     * The passphrase Room opens the database with. Hilt resolves it from
     * whatever thread first needs a DAO, and that is not always after the
     * application's warm-up: a boot receiver, the keep-alive worker, a sticky
     * service restart or the first composition can all get there first. Failing
     * there would crash the process, so the cache miss loads synchronously
     * instead — see [loadBlocking].
     */
    fun getPassphrase(): ByteArray = cachedPassphrase ?: loadBlocking()

    /**
     * Last-resort synchronous load. Unwrapping from the Keystore takes
     * hundreds of milliseconds on first use, which is why
     * `VMessengerApplication` warms it up off the main thread; reaching this
     * path means something needed the database before that finished, so it is
     * logged. [initialize] is idempotent, so a warm-up already in flight is
     * simply waited on rather than duplicated.
     */
    private fun loadBlocking(): ByteArray {
        AppLogger.warn(TAG, "database passphrase needed before init finished; loading it synchronously")
        return runBlocking {
            initialize()
            requireNotNull(cachedPassphrase) { "passphrase source returned nothing" }
        }
    }

    /** Forgets the cached passphrase (secure wipe); the next [initialize] loads again. */
    fun reset() {
        cachedPassphrase?.fill(0)
        cachedPassphrase = null
    }

    private companion object {
        const val TAG = "Database"
    }
}
