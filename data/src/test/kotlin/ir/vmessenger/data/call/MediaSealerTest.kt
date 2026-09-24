package ir.vmessenger.data.call

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaSealerTest {
    private val crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val key = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun `a sealed packet opens at the other end, and only there`() {
        val caller = MediaSealer(crypto, key.copyOf(), MediaDirection.CallerToCallee)
        val callee = MediaSealer(crypto, key.copyOf(), MediaDirection.CalleeToCaller)
        val otherCaller = MediaSealer(crypto, key.copyOf(), MediaDirection.CallerToCallee)

        val frame = caller.seal(byteArrayOf(1, 2, 3, 9), length = 3)

        assertContentEquals(byteArrayOf(1, 2, 3), callee.open(frame)?.payload)
        // Its own direction does not open: a frame reflected back at its sender is noise.
        assertNull(otherCaller.open(frame))
    }

    @Test
    fun `a greeting authenticates and carries nothing`() {
        val caller = MediaSealer(crypto, key.copyOf(), MediaDirection.CallerToCallee)
        val callee = MediaSealer(crypto, key.copyOf(), MediaDirection.CalleeToCaller)

        val opened = callee.open(caller.seal())

        assertTrue(opened?.greeting == true)
    }

    @Test
    fun `greetings and audio share one counter, so no two frames share a nonce`() {
        val caller = MediaSealer(crypto, key.copyOf(), MediaDirection.CallerToCallee)

        val sequences = List(5) { if (it % 2 == 0) caller.seal() else caller.seal(byteArrayOf(4), 1) }
            .map { CallMediaFrames.sequenceOf(it) }

        assertEquals(listOf(0, 1, 2, 3, 4), sequences)
    }

    @Test
    fun `a frame under another call's key does not open`() {
        val caller = MediaSealer(crypto, key.copyOf(), MediaDirection.CallerToCallee)
        val stranger = MediaSealer(crypto, ByteArray(32) { 1 }, MediaDirection.CalleeToCaller)

        assertNull(stranger.open(caller.seal(byteArrayOf(5), 1)))
        assertNull(stranger.open(ByteArray(3)))
    }

    @Test
    fun `both ends name a call's circuits alike, and no other call's`() {
        val prefix = CallCircuits.prefix(crypto, key)

        assertEquals(prefix, CallCircuits.prefix(crypto, key.copyOf()))
        assertNotEquals(prefix, CallCircuits.prefix(crypto, ByteArray(32)))
        assertTrue(prefix.startsWith("vmcall-") && prefix.endsWith("-"))
        assertEquals(prefix + "3", CallCircuits.id(prefix, 3))
        // Nothing of the key itself in the name the relay gets to see.
        assertTrue(key.none { byte -> prefix.contains("%02x".format(byte).repeat(4)) })
    }
}
