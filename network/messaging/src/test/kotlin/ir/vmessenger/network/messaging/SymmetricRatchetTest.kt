package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val root = crypto.randomBytes(32)
        val alice = ratchet.initFromRoot(root, isInitiator = true)
        val bob = ratchet.initFromRoot(root, isInitiator = false)
        val plaintext = "سلام vMessenger".toByteArray(Charsets.UTF_8)
        val ad = ByteArray(0)
        val sealed = ratchet.seal(alice, plaintext, ad)
        val opened = ratchet.open(bob, sealed, counter = 1, ad)
        assertNotNull(opened)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun sequentialMessagesOnSameSessionDecrypt() {
        val root = crypto.randomBytes(32)
        val alice = ratchet.initFromRoot(root, isInitiator = true)
        val bob = ratchet.initFromRoot(root, isInitiator = false)
        val ad = ByteArray(0)
        // Multiple frames on one session (e.g. post-handshake peer exchange) must
        // each decrypt at their incrementing counter, not just the first one.
        for (n in 1..5) {
            val body = "msg-$n".toByteArray()
            val sealed = ratchet.seal(alice, body, ad)
            val opened = ratchet.open(bob, sealed, counter = n.toLong(), ad)
            assertNotNull("message $n should decrypt", opened)
            assertArrayEquals(body, opened)
        }
        assertEquals(5L, bob.recvCounter)
    }

    @Test
    fun lostFrameDoesNotWedgeSession() {
        val root = crypto.randomBytes(32)
        val alice = ratchet.initFromRoot(root, isInitiator = true)
        val bob = ratchet.initFromRoot(root, isInitiator = false)
        val ad = ByteArray(0)
        // Sender seals 1,2,3,4; frame 2 is "lost" in transit. With the real
        // counter carried per frame, 3 and 4 must still decrypt (no wedge).
        val sealed = (1..4).map { ratchet.seal(alice, "m$it".toByteArray(), ad) }
        assertArrayEquals("m1".toByteArray(), ratchet.open(bob, sealed[0], counter = 1, ad))
        // counter 2 dropped; deliver 3 and 4 with their true counters
        assertArrayEquals("m3".toByteArray(), ratchet.open(bob, sealed[2], counter = 3, ad))
        assertArrayEquals("m4".toByteArray(), ratchet.open(bob, sealed[3], counter = 4, ad))
        assertEquals(4L, bob.recvCounter)
    }

    @Test
    fun outOfOrderMessagesDecrypt() {
        val root = crypto.randomBytes(32)
        val alice = ratchet.initFromRoot(root, isInitiator = true)
        val bob = ratchet.initFromRoot(root, isInitiator = false)
        val ad = ByteArray(0)
        val sealed = (1..3).map { ratchet.seal(alice, "m$it".toByteArray(), ad) }
        // Deliver 3, then 1, then 2 — all must decrypt (reliable transports can
        // still interleave post-handshake and chat frames).
        assertArrayEquals("m3".toByteArray(), ratchet.open(bob, sealed[2], 3, ad))
        assertArrayEquals("m1".toByteArray(), ratchet.open(bob, sealed[0], 1, ad))
        assertArrayEquals("m2".toByteArray(), ratchet.open(bob, sealed[1], 2, ad))
    }

    @Test
    fun replayedCounterRejected() {
        val root = crypto.randomBytes(32)
        val alice = ratchet.initFromRoot(root, isInitiator = true)
        val bob = ratchet.initFromRoot(root, isInitiator = false)
        val ad = ByteArray(0)
        val sealed = ratchet.seal(alice, "once".toByteArray(), ad)
        assertNotNull(ratchet.open(bob, sealed, counter = 1, ad))
        assertNull("replay of counter 1 must be rejected", ratchet.open(bob, sealed, counter = 1, ad))
    }

    @Test
    fun failedOpenDoesNotAdvanceRecvCounter() {
        val root = crypto.randomBytes(32)
        val alice = ratchet.initFromRoot(root, isInitiator = true)
        val bob = ratchet.initFromRoot(root, isInitiator = false)
        val ad = ByteArray(0)
        val sealed = ratchet.seal(alice, "real".toByteArray(), ad)
        val garbage = ratchet.seal(alice, "noise".toByteArray(), ad)
        assertNull(ratchet.open(bob, garbage, counter = 1, ad))
        assertEquals(0L, bob.recvCounter)
        val opened = ratchet.open(bob, sealed, counter = 1, ad)
        assertNotNull(opened)
        assertArrayEquals("real".toByteArray(), opened)
    }
}
