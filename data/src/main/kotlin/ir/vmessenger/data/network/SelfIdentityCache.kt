package ir.vmessenger.data.network

import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single in-memory owner of our unwrapped private keys.
 *
 * Every sender (outbox, receipts, location, contact requests, relay listener,
 * inbound handshakes) used to unwrap both private keys from the Keystore on
 * every operation and keep its own copies around. This cache unwraps once per
 * identity and hands out one [PeerIdentity]; [clear] zeroizes the key material
 * (secure wipe). A changed identity (regenerated, imported or wiped) is
 * detected by hash on the next [get], so a stale identity is never served.
 */
@Singleton
class SelfIdentityCache @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val cryptoEngine: CryptoEngine,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: PeerIdentity? = null

    /** Our identity with both private keys, or null while no identity (or no key material) exists. */
    suspend fun get(): PeerIdentity? {
        val identity = identityRepository.getIdentity()
        if (identity == null) {
            clear()
            return null
        }
        return cachedFor(identity.identityHash) ?: mutex.withLock {
            cachedFor(identity.identityHash)
                ?: load(identity.identityHash, identity.ed25519PublicKey, identity.x25519StaticPublicKey)
        }
    }

    private fun cachedFor(identityHash: ByteArray): PeerIdentity? =
        cached?.takeIf { it.identityHash.contentEquals(identityHash) }

    /** The Ed25519 private key for signing (relay listener proofs); null while unavailable. */
    suspend fun ed25519PrivateKey(): ByteArray? = get()?.ed25519PrivateKey

    /** Zeroizes and forgets the cached keys; the next [get] unwraps again. */
    fun clear() {
        val previous = cached ?: return
        cached = null
        previous.ed25519PrivateKey?.let(cryptoEngine::memzero)
        previous.x25519StaticPrivateKey?.let(cryptoEngine::memzero)
    }

    private suspend fun load(
        identityHash: ByteArray,
        ed25519PublicKey: ByteArray,
        x25519StaticPublicKey: ByteArray,
    ): PeerIdentity? {
        val ed25519Private = identityRepository.getEd25519PrivateKey()
        val x25519Private = identityRepository.getX25519StaticPrivateKey()
        if (ed25519Private == null || x25519Private == null) {
            ed25519Private?.let(cryptoEngine::memzero)
            x25519Private?.let(cryptoEngine::memzero)
            return null
        }
        clear()
        return PeerIdentity(
            identityHash = identityHash,
            ed25519PublicKey = ed25519PublicKey,
            x25519StaticPublicKey = x25519StaticPublicKey,
            ed25519PrivateKey = ed25519Private,
            x25519StaticPrivateKey = x25519Private,
        ).also { cached = it }
    }
}
