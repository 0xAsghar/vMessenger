package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.app.v1.NodeRole
import ir.vmessenger.core.proto.app.v1.SignedNodeRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SignedNodeRecordVerifierTest {
    private lateinit var cryptoEngine: CryptoEngine
    private lateinit var verifier: SignedNodeRecordVerifier
    private lateinit var signer: SignedNodeRecordSigner

    @Before
    fun setUp() {
        cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        verifier = SignedNodeRecordVerifier(cryptoEngine)
        signer = SignedNodeRecordSigner(cryptoEngine, verifier)
    }

    @Test
    fun validSignedRecordPassesVerification() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val expires = System.currentTimeMillis() + 60_000
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = listOf("relay"),
            expiresAtUnixMs = expires,
            ed25519PrivateKey = keyPair.privateKey,
        )
        assertTrue(verifier.verify(record, nowMs = System.currentTimeMillis()))
    }

    @Test
    fun expiredRecordFailsVerification() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = emptyList(),
            expiresAtUnixMs = System.currentTimeMillis() - 1,
            ed25519PrivateKey = keyPair.privateKey,
        )
        assertFalse(verifier.verify(record, nowMs = System.currentTimeMillis()))
    }

    @Test
    fun tamperedRecordFailsVerification() {
        val keyPair = cryptoEngine.generateEd25519KeyPair()
        val record = signer.sign(
            address = "wss://node.example/relay",
            role = NodeRole.NODE_ROLE_RELAY,
            publicKey = keyPair.publicKey,
            capabilities = emptyList(),
            expiresAtUnixMs = System.currentTimeMillis() + 60_000,
            ed25519PrivateKey = keyPair.privateKey,
        )
        val tampered = record.toBuilder().setAddress("wss://evil.example/relay").build()
        assertFalse(verifier.verify(tampered))
    }
}
