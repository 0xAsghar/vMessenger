package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.data.repository.FakeIdentityRepository
import ir.vmessenger.domain.repository.IdentityRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfIdentityCacheTest {
    private val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val repository = FakeIdentityRepository(cryptoEngine)

    /** Counts Keystore unwraps (the expensive part the cache exists to avoid). */
    private inner class CountingRepository : IdentityRepository by repository {
        var unwraps = 0

        override suspend fun getEd25519PrivateKey(): ByteArray? {
            unwraps++
            return repository.getEd25519PrivateKey()
        }

        override suspend fun getX25519StaticPrivateKey(): ByteArray? {
            unwraps++
            return repository.getX25519StaticPrivateKey()
        }
    }

    @Test
    fun unwrapsOnceAndServesTheSameIdentity() = runTest {
        val identity = InboundFixtures.installIdentity(repository, 0x01)
        val counting = CountingRepository()
        val cache = SelfIdentityCache(counting, cryptoEngine)

        val first = requireNotNull(cache.get())
        val second = cache.get()

        assertSame(first, second)
        assertEquals(2, counting.unwraps)
        assertArrayEquals(identity.identityHash, first.identityHash)
        assertArrayEquals(repository.ed25519Private, first.ed25519PrivateKey)
        assertArrayEquals(repository.x25519StaticPrivate, first.x25519StaticPrivateKey)
    }

    @Test
    fun clearZeroizesAndReloads() = runTest {
        InboundFixtures.installIdentity(repository, 0x01)
        val counting = CountingRepository()
        val cache = SelfIdentityCache(counting, cryptoEngine)
        val served = requireNotNull(cache.get())

        cache.clear()

        assertTrue(served.ed25519PrivateKey!!.all { it == 0.toByte() })
        assertTrue(served.x25519StaticPrivateKey!!.all { it == 0.toByte() })
        assertNotNull(cache.ed25519PrivateKey())
        assertEquals(4, counting.unwraps)
    }

    @Test
    fun nullWithoutIdentityOrKeys() = runTest {
        val cache = SelfIdentityCache(repository, cryptoEngine)
        assertNull(cache.get())

        repository.identity = InboundFixtures.identity(0x01)
        assertNull(cache.get())

        repository.ed25519Private = ByteArray(64) { 1 }
        repository.x25519StaticPrivate = ByteArray(32) { 2 }
        assertNotNull(cache.get())
    }

    @Test
    fun identityChangeInvalidates() = runTest {
        InboundFixtures.installIdentity(repository, 0x01)
        val cache = SelfIdentityCache(repository, cryptoEngine)
        val before = requireNotNull(cache.get())

        repository.wipeIdentity()
        assertNull(cache.get())
        assertTrue(before.ed25519PrivateKey!!.all { it == 0.toByte() })

        val replaced = InboundFixtures.installIdentity(repository, 0x05)
        val after = requireNotNull(cache.get())
        assertArrayEquals(replaced.identityHash, after.identityHash)
    }
}
