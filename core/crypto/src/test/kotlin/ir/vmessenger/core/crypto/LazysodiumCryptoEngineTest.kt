package ir.vmessenger.core.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun argon2idDeterministic() {
        val passphrase = "correct horse battery".toByteArray()
        val salt = ByteArray(16) { it.toByte() }
        val a = engine.pwhashArgon2id(passphrase, salt, opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)
        val b = engine.pwhashArgon2id(passphrase, salt, opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)

        val otherSalt = ByteArray(16) { (it + 1).toByte() }
        val c = engine.pwhashArgon2id(passphrase, otherSalt, opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)
        assertFalse(a.contentEquals(c))

        val moreOps = engine.pwhashArgon2id(passphrase, salt, opsLimit = 2, memLimitBytes = 8L * 1024 * 1024)
        assertFalse(a.contentEquals(moreOps))
    }

    @Test
    fun xchachaRoundTripAndTamperDetected() {
        val key = engine.randomBytes(32)
        val nonce = engine.randomBytes(24)
        val ad = "header".toByteArray()
        val plaintext = "secret payload".toByteArray()
        val sealed = engine.xchacha20Poly1305Seal(key, nonce, plaintext, ad)
        assertEquals(plaintext.size + 16, sealed.size)
        assertArrayEquals(plaintext, engine.xchacha20Poly1305Open(key, nonce, sealed, ad))

        val tampered = sealed.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertNull(engine.xchacha20Poly1305Open(key, nonce, tampered, ad))
        assertNull(engine.xchacha20Poly1305Open(key, nonce, sealed, "other".toByteArray()))
        assertNull(engine.xchacha20Poly1305Open(key, engine.randomBytes(24), sealed, ad))
        assertNull(engine.xchacha20Poly1305Open(key, nonce, sealed.copyOf(15), ad))
    }

    @Test
    fun x25519PublicFromPrivateMatchesKeypair() {
        val keyPair = engine.generateX25519KeyPair()
        assertArrayEquals(keyPair.publicKey, engine.x25519PublicFromPrivate(keyPair.privateKey))
    }

    @Test
    fun ed25519PublicFromPrivateMatchesKeypair() {
        val keyPair = engine.generateEd25519KeyPair()
        assertArrayEquals(keyPair.publicKey, engine.ed25519PublicFromPrivate(keyPair.privateKey))
        val seed = keyPair.privateKey.copyOf(32)
        assertArrayEquals(keyPair.publicKey, engine.ed25519PublicFromPrivate(seed))
    }

    @Test
    fun memzeroClearsBytes() {
        val bytes = engine.randomBytes(32)
        engine.memzero(bytes)
        assertArrayEquals(ByteArray(32), bytes)
    }
}
