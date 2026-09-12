package ir.vmessenger.core.crypto.pairing

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.proto.wire.v1.PairingDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingDescriptorCodecTest {
    private lateinit var crypto: LazysodiumCryptoEngine
    private lateinit var codec: PairingDescriptorCodec
    private lateinit var keyPair: KeyPair
    private lateinit var userHash: String

    @Before
    fun setUp() {
        crypto = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        codec = PairingDescriptorCodec(crypto)
        keyPair = crypto.generateEd25519KeyPair()
        userHash = UserHashEncoder.encode(UserHashEncoder.identityHashFromPublicKey(keyPair.publicKey))
    }

    private fun signed(label: String = "علی") =
        codec.createSigned(keyPair.publicKey, userHash, label, keyPair.privateKey)

    @Test
    fun versionTwoVerifies() {
        val descriptor = signed()
        assertEquals(2, descriptor.version)
        assertEquals(64, descriptor.signature.size())
        assertTrue(codec.verify(descriptor))
    }

    @Test
    fun versionOneRejected() {
        // A v1 descriptor signed the raw protobuf bytes; the codec must not fall back to that.
        val unsigned = PairingDescriptor.newBuilder()
            .setIdentityPub(ByteString.copyFrom(keyPair.publicKey))
            .setUserHash(userHash)
            .setDisplayLabel("legacy")
            .setVersion(1)
            .build()
        val legacySignature = crypto.signEd25519(unsigned.toByteArray(), keyPair.privateKey)
        val legacy = unsigned.toBuilder().setSignature(ByteString.copyFrom(legacySignature)).build()
        assertFalse(codec.verify(legacy))

        // A v2 signature re-labelled as version 1 (or 3) fails too: the version is inside the transcript.
        assertFalse(codec.verify(signed().toBuilder().setVersion(1).build()))
        assertFalse(codec.verify(signed().toBuilder().setVersion(3).build()))
    }

    @Test
    fun userHashMismatchRejected() {
        val other = crypto.generateEd25519KeyPair()
        val otherHash = UserHashEncoder.encode(UserHashEncoder.identityHashFromPublicKey(other.publicKey))
        // Correctly signed by the key owner, but claims someone else's user hash.
        val mismatched = codec.createSigned(keyPair.publicKey, otherHash, "x", keyPair.privateKey)
        assertFalse(codec.verify(mismatched))
        // Legacy vm1 strings are not decodable, so a v2 descriptor carrying one never verifies.
        val legacyHash = "vm1-" + userHash.removePrefix("vm2-")
        assertFalse(codec.verify(codec.createSigned(keyPair.publicKey, legacyHash, "x", keyPair.privateKey)))
    }

    @Test
    fun tamperedFieldsRejected() {
        val descriptor = signed()
        assertFalse(codec.verify(descriptor.toBuilder().setDisplayLabel("mallory").build()))
        assertFalse(codec.verify(descriptor.toBuilder().clearSignature().build()))
        val other = crypto.generateEd25519KeyPair()
        assertFalse(codec.verify(descriptor.toBuilder().setIdentityPub(ByteString.copyFrom(other.publicKey)).build()))
        assertFalse(codec.verify(descriptor.toBuilder().setIdentityPub(ByteString.copyFrom(ByteArray(31))).build()))
    }

    @Test
    fun transcriptIsDomainSeparatedAndLengthPrefixed() {
        val descriptor = signed("ab")
        val transcript = codec.buildTranscript(descriptor)
        val tag = "vmessenger-pairing-v2".toByteArray()
        assertArrayEquals(tag, transcript.copyOf(tag.size))
        val expectedSize = tag.size + 4 + 32 + 4 + userHash.length + 4 + 2 + 4
        assertEquals(expectedSize, transcript.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 2), transcript.copyOfRange(transcript.size - 4, transcript.size))
        // Signature is over the transcript, not over the protobuf encoding.
        assertTrue(crypto.verifyEd25519(transcript, descriptor.signature.toByteArray(), keyPair.publicKey))
        val proto = descriptor.toBuilder().clearSignature().build().toByteArray()
        assertFalse(crypto.verifyEd25519(proto, descriptor.signature.toByteArray(), keyPair.publicKey))
    }

    @Test
    fun base64RoundTripOnJvm() {
        val descriptor = signed()
        val encoded = codec.encodeBase64(descriptor)
        assertFalse(encoded.contains('\n'))
        assertEquals(descriptor, codec.decodeBase64(encoded))
        assertEquals(descriptor, codec.decodeBase64(" $encoded\n"))
        assertNull(codec.decodeBase64("not base64!"))
    }
}
