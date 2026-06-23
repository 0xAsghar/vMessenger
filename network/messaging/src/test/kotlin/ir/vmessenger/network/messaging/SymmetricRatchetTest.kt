package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
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
}
