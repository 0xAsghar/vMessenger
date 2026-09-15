package ir.vmessenger.core.database

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.keystore.KeyStoreKeyManager
import ir.vmessenger.core.datastore.AppLockPreferences
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
    private val lockPreferences: AppLockPreferences,
) : DatabasePassphraseSource {
    override suspend fun load(): ByteArray {
        val wrapped = securityPreferences.getWrappedDbPassphrase()
        if (wrapped != null && wrapped.isNotEmpty()) {
            return keyStoreKeyManager.unwrap(KeyStoreKeyManager.ALIAS_DATABASE, wrapped)
        }
        // No ordinary copy is the *normal* state under strict app lock, and minting a fresh
        // passphrase here would abandon the real database rather than open it. This is the
        // single most destructive thing this class could do, so it refuses instead.
        check(lockPreferences.getStrictWrappedPassphrase() == null) {
            "the database passphrase is behind the app lock; authenticate before opening it"
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

    /**
     * True while strict app-lock is holding the database shut.
     *
     * Separate from the cache being empty, and that distinction is the whole point: an empty cache
     * means "load it", a locked provider means "do not, and say so". Without it [lock] would be
     * theatre — the very next DAO access would quietly unwrap the key again.
     */
    @Volatile
    private var locked = false

    suspend fun initialize() {
        // Locked is not "not loaded yet": loading is exactly what must not happen.
        if (locked || cachedPassphrase != null) return
        mutex.withLock {
            if (!locked && cachedPassphrase == null) cachedPassphrase = source.load()
        }
    }

    /**
     * Hands the provider a passphrase that was unwrapped elsewhere, and opens the gate.
     *
     * Strict app lock uses this after authenticating: the passphrase comes back from a Keystore key
     * the hardware would not touch a moment earlier, and it goes into memory only. Persisting an
     * ordinary copy at that point would put the key back within reach of anything that can read the
     * app's files, which is the whole thing strict mode exists to prevent.
     */
    fun provide(passphrase: ByteArray) {
        // The outgoing array is dropped, never zeroed — see [lock] for the reason, which is the
        // same one: SQLCipher is still holding it.
        cachedPassphrase = passphrase
        locked = false
    }

    /**
     * Shuts the database until the user authenticates again (strict mode only).
     *
     * The caller must close the Room database first: SQLCipher holds the key inside its open
     * connection, so dropping this cache alone leaves an already-open handle reading and writing
     * perfectly well.
     *
     * **This drops the reference and deliberately does not zero the array.** The array handed to
     * `DatabaseModule.provideDatabasePassphrase` is this same object, and it lives on inside
     * SQLCipher's open helper for the life of the process — there is exactly one copy, not two.
     * Zeroing it therefore does not remove the key from memory, it destroys the only key the
     * process has: Room reopens the file on the next query after an unlock, hands SQLCipher
     * thirty-two zero bytes, and the user's database comes back as
     * `SQLiteNotADatabaseException: file is not a database`. That is what shipped, and it turned
     * the first lock/unlock cycle under strict mode into a crash loop over intact data.
     *
     * So the in-memory key survives a strict lock, and [docs/Security.md] §7.4 says so. What
     * strict mode protects is the key **at rest**: on disk the passphrase is wrapped under a
     * Keystore key that will not unwrap without user authentication, so a cold start — a stolen
     * device, a rebooted one, an app the system has killed — cannot open the database at all.
     * Removing it from a *running* process as well would mean rebuilding the Room instance after
     * every unlock, which its singleton DAOs cannot express, or ending the process on lock.
     * Neither is a comment's decision to make.
     */
    fun lock() {
        locked = true
        cachedPassphrase = null
    }

    /** Lets the database be opened again, after a successful authentication. */
    fun unlock() {
        locked = false
    }

    val isLocked: Boolean get() = locked

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
     * The passphrase, or null while the app is locked.
     *
     * Background callers — the boot receiver, the keep-alive worker, a sticky service restart —
     * must use this rather than [getPassphrase] and treat null as "stay down". Asking them to
     * survive an exception instead would turn a locked phone into a crash loop.
     */
    fun getPassphraseOrNull(): ByteArray? = if (locked) null else cachedPassphrase

    /**
     * Last-resort synchronous load. Unwrapping from the Keystore takes
     * hundreds of milliseconds on first use, which is why
     * `VMessengerApplication` warms it up off the main thread; reaching this
     * path means something needed the database before that finished, so it is
     * logged. [initialize] is idempotent, so a warm-up already in flight is
     * simply waited on rather than duplicated.
     */
    private fun loadBlocking(): ByteArray {
        check(!locked) { "the database is locked; the user has not authenticated" }
        AppLogger.warn(TAG, "database passphrase needed before init finished; loading it synchronously")
        return runBlocking {
            initialize()
            requireNotNull(cachedPassphrase) { "passphrase source returned nothing" }
        }
    }

    /**
     * Forgets the cached passphrase (secure wipe); the next [initialize] loads again.
     *
     * This one *does* zero, unlike [lock], and for a reason that only applies here: the wipe
     * destroys the database file and the Keystore key and then ends the process, so there is no
     * later reopen for a zeroed key to break.
     */
    fun reset() {
        cachedPassphrase?.fill(0)
        cachedPassphrase = null
    }

    private companion object {
        const val TAG = "Database"
    }
}
