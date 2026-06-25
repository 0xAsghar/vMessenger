package ir.vmessenger.network.messaging

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.network.RelayProof
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHelloProofInteropTest {
    private val sodium = LazySodiumJava(SodiumJava())
    private val crypto = LazysodiumCryptoEngine(sodium)

    @Test
    fun listenerProofVerifiesWithNodeStyleSodium() {
        val keyPair = crypto.generateEd25519KeyPair()
        val identityHash = crypto.sha256(keyPair.publicKey)
        val ts = System.currentTimeMillis()
        val transcript = RelayProof.buildListenerProofTranscript(identityHash, ts)
        val proof = crypto.signEd25519(transcript, keyPair.privateKey)
        assertTrue(
            sodium.cryptoSignVerifyDetached(
                proof,
                transcript,
                transcript.size,
                keyPair.publicKey,
            ),
        )
    }
}
