package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.Canonical
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SymmetricRatchetTest {
    private lateinit var ratchet: SymmetricRatchet
    private lateinit var crypto: LazysodiumCryptoEngine

    @Before
    fun setup() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        ratchet = SymmetricRatchet(crypto)
    }

    @Test
    fun sealAndOpenRoundTrip() {
        val (alice, bob) = pair()
        val plaintext = "سلام vMessenger".toByteArray(Charsets.UTF_8)
        val sealed = ratchet.seal(alice, plaintext, AD)
        val opened = ratchet.open(bob, sealed, counter = 1, AD)
        assertNotNull(opened)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun sequentialMessagesOnSameSessionDecrypt() {
        val (alice, bob) = pair()
        // Multiple frames on one session (e.g. post-handshake peer exchange) must
        // each decrypt at their incrementing counter, not just the first one.
        for (n in 1..5) {
            val body = "msg-$n".toByteArray()
            val sealed = ratchet.seal(alice, body, AD)
            val opened = ratchet.open(bob, sealed, counter = n.toLong(), AD)
            assertNotNull("message $n should decrypt", opened)
            assertArrayEquals(body, opened)
        }
        assertEquals(5L, bob.recvCounter)
        assertTrue(bob.skipped.isEmpty())
    }

    @Test
    fun lostFrameDoesNotWedgeSession() {
        val (alice, bob) = pair()
        // Sender seals 1,2,3,4; frame 2 is "lost" in transit. With the real
        // counter carried per frame, 3 and 4 must still decrypt (no wedge).
        val sealed = (1..4).map { ratchet.seal(alice, "m$it".toByteArray(), AD) }
        assertArrayEquals("m1".toByteArray(), ratchet.open(bob, sealed[0], counter = 1, AD))
        // counter 2 dropped; deliver 3 and 4 with their true counters
        assertArrayEquals("m3".toByteArray(), ratchet.open(bob, sealed[2], counter = 3, AD))
        assertArrayEquals("m4".toByteArray(), ratchet.open(bob, sealed[3], counter = 4, AD))
        assertEquals(4L, bob.recvCounter)
    }

    @Test
    fun outOfOrderMessagesDecrypt() {
        val (alice, bob) = pair()
        val sealed = (1..3).map { ratchet.seal(alice, "m$it".toByteArray(), AD) }
        // Deliver 3, then 1, then 2 — all must decrypt (reliable transports can
        // still interleave post-handshake and chat frames).
        assertArrayEquals("m3".toByteArray(), ratchet.open(bob, sealed[2], 3, AD))
        assertArrayEquals("m1".toByteArray(), ratchet.open(bob, sealed[0], 1, AD))
        assertArrayEquals("m2".toByteArray(), ratchet.open(bob, sealed[1], 2, AD))
    }

    @Test
    fun replayedCounterRejected() {
        val (alice, bob) = pair()
        val sealed = ratchet.seal(alice, "once".toByteArray(), AD)
        assertNotNull(ratchet.open(bob, sealed, counter = 1, AD))
        assertNull("replay of counter 1 must be rejected", ratchet.open(bob, sealed, counter = 1, AD))
    }

    @Test
    fun failedOpenDoesNotAdvanceRecvCounter() {
        val (alice, bob) = pair()
        val sealed = ratchet.seal(alice, "real".toByteArray(), AD)
        val garbage = ratchet.seal(alice, "noise".toByteArray(), AD)
        assertNull(ratchet.open(bob, garbage, counter = 1, AD))
        assertEquals(0L, bob.recvCounter)
        val opened = ratchet.open(bob, sealed, counter = 1, AD)
        assertNotNull(opened)
        assertArrayEquals("real".toByteArray(), opened)
    }

    @Test
    fun counterBeyondMaxSkipRejectedWithoutKdf() {
        val counting = CountingCryptoEngine(crypto)
        val countingRatchet = SymmetricRatchet(counting)
        val root = crypto.randomBytes(32)
        val alice = countingRatchet.initFromRoot(root, isInitiator = true)
        val bob = countingRatchet.initFromRoot(root, isInitiator = false)
        val sealed = countingRatchet.seal(alice, "far".toByteArray(), AD)
        counting.hkdfCalls = 0

        val tooFar = bob.recvCounter + SymmetricRatchet.MAX_SKIP + 1
        assertNull(countingRatchet.open(bob, sealed, tooFar, AD))
        assertEquals("no KDF work for a counter beyond MAX_SKIP", 0, counting.hkdfCalls)
        assertEquals(0L, bob.recvCounter)
        assertTrue(bob.skipped.isEmpty())

        // Exactly MAX_SKIP ahead is still derived (and fails only on the AEAD).
        assertNull(countingRatchet.open(bob, sealed, bob.recvCounter + SymmetricRatchet.MAX_SKIP, AD))
        assertTrue(counting.hkdfCalls > 0)
    }

    @Test
    fun skippedKeysAllowOutOfOrderThenReplayRejected() {
        val (alice, bob) = pair()
        val sealed = (1..3).map { ratchet.seal(alice, "m$it".toByteArray(), AD) }
        assertArrayEquals("m3".toByteArray(), ratchet.open(bob, sealed[2], 3, AD))
        assertEquals(setOf(1L, 2L), bob.skipped.keys)
        assertArrayEquals("m1".toByteArray(), ratchet.open(bob, sealed[0], 1, AD))
        assertArrayEquals("m2".toByteArray(), ratchet.open(bob, sealed[1], 2, AD))
        assertTrue(bob.skipped.isEmpty())
        assertNull("replay of 1", ratchet.open(bob, sealed[0], 1, AD))
        assertNull("replay of 2", ratchet.open(bob, sealed[1], 2, AD))
        assertNull("replay of 3", ratchet.open(bob, sealed[2], 3, AD))
    }

    @Test
    fun recvChainAdvancesAndUsedKeysWiped() {
        val (alice, bob) = pair()
        val initialRecvChain = bob.recvChainKey
        val snapshot = initialRecvChain.copyOf()
        val sealed = (1..2).map { ratchet.seal(alice, "m$it".toByteArray(), AD) }

        assertArrayEquals("m2".toByteArray(), ratchet.open(bob, sealed[1], 2, AD))
        assertFalse("recv chain must advance", bob.recvChainKey.contentEquals(snapshot))
        assertTrue("old chain key wiped", initialRecvChain.all { it == 0.toByte() })
        assertEquals(2L, bob.recvCounter)

        val skippedKey = bob.skipped[1L]!!
        assertArrayEquals("m1".toByteArray(), ratchet.open(bob, sealed[0], 1, AD))
        assertTrue("used skipped key wiped", skippedKey.all { it == 0.toByte() })
        assertFalse(bob.skipped.containsKey(1L))
    }

    @Test
    fun failedOpenDoesNotConsumeSkippedKey() {
        val (alice, bob) = pair()
        val sealed = (1..2).map { ratchet.seal(alice, "m$it".toByteArray(), AD) }
        assertArrayEquals("m2".toByteArray(), ratchet.open(bob, sealed[1], 2, AD))
        assertTrue(bob.skipped.containsKey(1L))
        val garbage = sealed[0].copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(ratchet.open(bob, garbage, 1, AD))
        assertTrue("skipped key survives a failed open", bob.skipped.containsKey(1L))
        assertArrayEquals("m1".toByteArray(), ratchet.open(bob, sealed[0], 1, AD))
    }

    @Test
    fun skippedStoreBounded() {
        val (alice, bob) = pair()
        // Opening the furthest allowed counter parks MAX_SKIP-1 keys; a second
        // jump of the same size overflows the store and evicts the oldest.
        val first = (1..SymmetricRatchet.MAX_SKIP).map { ratchet.seal(alice, "a$it".toByteArray(), AD) }
        assertNotNull(ratchet.open(bob, first.last(), SymmetricRatchet.MAX_SKIP, AD))
        assertEquals((SymmetricRatchet.MAX_SKIP - 1).toInt(), bob.skipped.size)
        val oldestKey = bob.skipped[1L]!!

        val second = (1..SymmetricRatchet.MAX_SKIP).map { ratchet.seal(alice, "b$it".toByteArray(), AD) }
        val lastCounter = bob.recvCounter + SymmetricRatchet.MAX_SKIP
        assertNotNull(ratchet.open(bob, second.last(), lastCounter, AD))
        assertEquals(SymmetricRatchet.MAX_SKIPPED_STORE, bob.skipped.size)
        assertFalse("oldest skipped key evicted", bob.skipped.containsKey(1L))
        assertTrue("evicted key wiped", oldestKey.all { it == 0.toByte() })
        assertNull("frame whose key was evicted can no longer be opened", ratchet.open(bob, first[0], 1, AD))
        val newest = lastCounter - 1
        assertArrayEquals(
            "b${SymmetricRatchet.MAX_SKIP - 1}".toByteArray(),
            ratchet.open(bob, second[second.size - 2], newest, AD),
        )
    }

    @Test
    fun counterIsBoundByAad() {
        val (alice, bob) = pair()
        val chainKeyBefore = bob.recvChainKey.copyOf()
        val sealed = ratchet.seal(alice, "bound".toByteArray(), AD)
        // Re-derive mk_1 exactly as the ratchet does and open with the raw AEAD:
        // the counter must be part of the associated data.
        val mk1 = crypto.hkdfSha256(
            chainKeyBefore,
            ByteArray(0),
            "vmsg-v2-mk".toByteArray() + Canonical.u64be(1),
            32,
        )
        assertNotNull(crypto.open(sealed, mk1, AD + Canonical.u64be(1)))
        assertNull(crypto.open(sealed, mk1, AD + Canonical.u64be(2)))
        assertNull(crypto.open(sealed, mk1, AD))
        // And through the ratchet, a different frame-type prefix fails too.
        assertNull(ratchet.open(bob, sealed, 1, AD + byteArrayOf(1)))
        assertNotNull(ratchet.open(bob, sealed, 1, AD))
    }

    @Test
    fun wipeZeroizesEverything() {
        val (alice, bob) = pair()
        val sealed = (1..3).map { ratchet.seal(alice, "m$it".toByteArray(), AD) }
        assertNotNull(ratchet.open(bob, sealed[2], 3, AD))
        val skipped = bob.skipped.values.toList()
        val send = bob.sendChainKey
        val recv = bob.recvChainKey
        bob.wipe()
        assertTrue(send.all { it == 0.toByte() })
        assertTrue(recv.all { it == 0.toByte() })
        assertTrue(skipped.all { key -> key.all { it == 0.toByte() } })
        assertTrue(bob.skipped.isEmpty())
    }

    @Test
    fun wipedStateRefusesSealAndOpen() {
        val (alice, bob) = pair()
        val genuine = ratchet.seal(alice, "m1".toByteArray(), AD)
        bob.wipe()
        assertTrue(bob.wiped)
        assertNull(ratchet.open(bob, genuine, counter = 1, AD))
        // After the wipe the chain is all-zero, so mk_1 = HKDF(0^32, "vmsg-v2-mk" || u64be(1))
        // is publicly computable; a frame sealed under it must still be refused.
        val zeroChainMessageKey = crypto.hkdfSha256(
            ByteArray(32),
            ByteArray(0),
            "vmsg-v2-mk".toByteArray() + Canonical.u64be(1),
            32,
        )
        val forged = crypto.seal("forged".toByteArray(), zeroChainMessageKey, AD + Canonical.u64be(1))
        assertNull(ratchet.open(bob, forged, counter = 1, AD))
        assertEquals(0L, bob.recvCounter)
        assertThrows(IllegalStateException::class.java) { ratchet.seal(bob, "m2".toByteArray(), AD) }
        assertEquals(0L, bob.sendCounter)
    }

    private fun pair(): Pair<RatchetState, RatchetState> {
        val root = crypto.randomBytes(32)
        return ratchet.initFromRoot(root, isInitiator = true) to ratchet.initFromRoot(root, isInitiator = false)
    }

    private class CountingCryptoEngine(private val delegate: CryptoEngine) : CryptoEngine by delegate {
        var hkdfCalls = 0

        override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            hkdfCalls++
            return delegate.hkdfSha256(ikm, salt, info, length)
        }
    }

    private companion object {
        val AD = "test-ad".toByteArray()
    }
}
