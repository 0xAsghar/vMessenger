package ir.vmessenger.network.dht

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EndpointRecordVerifierTest {
    private lateinit var signer: EndpointRecordSigner
    private lateinit var verifier: EndpointRecordVerifier
    private lateinit var crypto: LazysodiumCryptoEngine

    @Before
    fun setup() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        signer = EndpointRecordSigner(crypto)
        verifier = EndpointRecordVerifier(crypto)
    }

    @Test
    fun verifyValidRecord() {
        val ed = crypto.generateEd25519KeyPair()
        val hash = crypto.sha256(ed.publicKey)
        val record = signer.sign(
            identityHash = hash,
            identityPub = ed.publicKey,
            endpoints = listOf(Endpoint(TransportIds.INTERNET, "127.0.0.1:46555")),
            publishedAtUnixMs = System.currentTimeMillis(),
            ttlMs = 60_000,
            sequence = 1,
            ed25519PrivateKey = ed.privateKey,
        )
        assertTrue(verifier.verify(record))
    }

    @Test
    fun rejectTamperedRecord() {
        val ed = crypto.generateEd25519KeyPair()
        val hash = crypto.sha256(ed.publicKey)
        val record = signer.sign(
            identityHash = hash,
            identityPub = ed.publicKey,
            endpoints = listOf(Endpoint(TransportIds.INTERNET, "127.0.0.1:46555")),
            publishedAtUnixMs = System.currentTimeMillis(),
            ttlMs = 60_000,
            sequence = 1,
            ed25519PrivateKey = ed.privateKey,
        )
        val tampered = record.toBuilder().setSequence(99).build()
        assertFalse(verifier.verify(tampered))
    }
}
