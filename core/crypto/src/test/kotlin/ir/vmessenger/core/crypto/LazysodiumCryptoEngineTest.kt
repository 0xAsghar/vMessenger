package ir.vmessenger.core.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LazysodiumCryptoEngineTest {
    private lateinit var engine: LazysodiumCryptoEngine

    @Before
    fun setUp() {
        engine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    }

    @Test
    fun ed25519SignVerifyRoundTrip() {
        val keyPair = engine.generateEd25519KeyPair()
        val message = "vMessenger test".toByteArray()
        val signature = engine.signEd25519(message, keyPair.privateKey)
        assertTrue(engine.verifyEd25519(message, signature, keyPair.publicKey))
    }

    @Test
    fun ed25519KnownVectorVerify() {
        val keyPair = engine.generateEd25519KeyPair()
        val message = byteArrayOf(0x01, 0x02, 0x03)
        val signature = engine.signEd25519(message, keyPair.privateKey)
        assertTrue(engine.verifyEd25519(message, signature, keyPair.publicKey))
    }

    @Test
    fun sealOpenRoundTrip() {
        val key = engine.randomBytes(32)
        val plaintext = "secret payload".toByteArray()
        val sealed = engine.seal(plaintext, key)
        val opened = engine.open(sealed, key)
        assertNotNull(opened)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun sealTamperRejected() {
        val key = engine.randomBytes(32)
        val sealed = engine.seal("data".toByteArray(), key).copyOf()
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 0xFF).toByte()
        assertFalse(engine.open(sealed, key) != null && engine.open(sealed, key)!!.contentEquals("data".toByteArray()))
    }

    @Test
    fun hkdfDeterministic() {
        val ikm = "input".toByteArray()
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        val a = engine.hkdfSha256(ikm, salt, info, 32)
        val b = engine.hkdfSha256(ikm, salt, info, 32)
        assertArrayEquals(a, b)
        assertTrue(a.size == 32)
    }
}
