package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.dht.v1.EndpointRecord
import ir.vmessenger.network.dht.EndpointRecordSigner
import ir.vmessenger.network.dht.EndpointRecordVerifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerEndpointCacheImplTest {
    private val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val signer = EndpointRecordSigner(crypto)
    private val dao = FakeEndpointCacheDao()
    private val cache = PeerEndpointCacheImpl(dao, EndpointRecordVerifier(crypto))
    private val identity = crypto.generateEd25519KeyPair()
    private val fullHash = crypto.sha256(identity.publicKey)
    private val endpoints = listOf(Endpoint(TransportIds.INTERNET, "203.0.113.7:48555"))

    private fun record(sequence: Long = 1, ttlMs: Long = 60_000): EndpointRecord = signer.sign(
        identityHash = fullHash,
        identityPub = identity.publicKey,
        endpoints = endpoints,
        publishedAtUnixMs = System.currentTimeMillis(),
        ttlMs = ttlMs,
        sequence = sequence,
        ed25519PrivateKey = identity.privateKey,
    )

    @Test
    fun lookupAndStoreUseRoutingHash() = runTest {
        cache.store(record())

        // Rows are keyed by the zero-padded 16-byte routing prefix, not the raw 32-byte hash.
        val row = dao.entries.single()
        assertArrayEquals(IdentityHashMatcher.routingHash(fullHash), row.identityHash)
        assertNull(dao.get(fullHash))

        // A lookup with the full hash and one with the User-Hash-derived partial hash both hit.
        val partial = IdentityHashMatcher.routingHash(fullHash)
        val fromFull = cache.lookup(fullHash)
        val fromPartial = cache.lookup(partial)
        assertEquals(endpoints.map { it.transport to it.address }, fromFull?.map { it.transport to it.address })
        assertEquals(fromFull, fromPartial)

        cache.evict(partial)
        assertTrue(dao.entries.isEmpty())
        assertNull(cache.lookup(fullHash))
    }

    @Test
    fun olderRecordNeverOverwritesNewer() = runTest {
        cache.store(record(sequence = 5))
        cache.store(record(sequence = 3))
        assertEquals(5L, dao.entries.single().sequence)
        cache.store(record(sequence = 6))
        assertEquals(6L, dao.entries.single().sequence)
    }

    @Test
    fun unverifiableRecordIsNotCached() = runTest {
        val tampered = record().toBuilder().setSequence(99).build()
        cache.store(tampered)
        assertTrue(dao.entries.isEmpty())
        // A well-signed record whose TTL is out of bounds is refused as well.
        cache.store(record(ttlMs = EndpointRecordVerifier.MAX_TTL_MS + 1))
        assertTrue(dao.entries.isEmpty())
    }
}
