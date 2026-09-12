package ir.vmessenger.core.datastore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64

/** Backing store for the persisted DHT node id (Base64 text); implemented over DataStore in production. */
interface DhtNodeIdStorage {
    suspend fun read(): String?

    suspend fun write(encoded: String)
}

/**
 * Per-device random DHT node id. Every install used to share one hard-coded id,
 * which made all Android participants collide in every routing table; the id is
 * now 32 random bytes generated once and persisted (Base64) for the life of the
 * install. Pure JVM logic so the persistence contract is unit-testable.
 */
object DhtNodeId {
    const val SIZE_BYTES = 32

    private val mutex = Mutex()

    suspend fun getOrCreate(storage: DhtNodeIdStorage, random: SecureRandom = SecureRandom()): ByteArray =
        mutex.withLock {
            val existing = storage.read()?.let(::decode)
            if (existing != null && existing.size == SIZE_BYTES) return@withLock existing
            val fresh = ByteArray(SIZE_BYTES).also(random::nextBytes)
            storage.write(Base64.getEncoder().withoutPadding().encodeToString(fresh))
            fresh
        }

    private fun decode(encoded: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
}
