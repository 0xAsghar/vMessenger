package ir.vmessenger.node

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature

/** An Ed25519 identity for tests, signing with the JDK the way the app signs with libsodium. */
internal class TestIdentity private constructor(val pub: ByteArray, private val key: PrivateKey) {

    val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(pub)

    fun sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(key)
        update(message)
        sign()
    }

    companion object {
        /** The raw key is the last 32 bytes of its X.509 `SubjectPublicKeyInfo` encoding. */
        fun generate(): TestIdentity {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val encoded = pair.public.encoded
            val raw = encoded.copyOfRange(encoded.size - Ed25519Verifier.PUBLIC_KEY_SIZE, encoded.size)
            return TestIdentity(raw, pair.private)
        }
    }
}
