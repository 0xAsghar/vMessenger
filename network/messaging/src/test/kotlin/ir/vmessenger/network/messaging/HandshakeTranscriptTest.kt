package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import ir.vmessenger.core.proto.wire.v1.Capabilities
import ir.vmessenger.core.proto.wire.v1.HandshakeMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTranscriptTest {
    @Test
    fun canonicalIsLengthPrefixedAndOrderSensitive() {
        // Same raw bytes split differently across fields must not collide.
        val a = HandshakeMessage.newBuilder()
            .setStep(2)
            .setEphemeralPub(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setStaticPub(ByteString.EMPTY)
            .build()
        val b = HandshakeMessage.newBuilder()
            .setStep(2)
            .setEphemeralPub(ByteString.copyFrom(byteArrayOf(1, 2)))
            .setStaticPub(ByteString.copyFrom(byteArrayOf(3)))
            .build()
        assertFalse(HandshakeTranscript.canonical(a).contentEquals(HandshakeTranscript.canonical(b)))

        // Feature order is part of the transcript.
        val caps1 = Capabilities.newBuilder().setProtocolMajor(2).addFeatures("x").addFeatures("y").build()
        val caps2 = Capabilities.newBuilder().setProtocolMajor(2).addFeatures("y").addFeatures("x").build()
        assertFalse(
            HandshakeTranscript.capabilitiesBytes(caps1).contentEquals(HandshakeTranscript.capabilitiesBytes(caps2)),
        )

        // Layout: u32be(step) || lp(eph) || lp(static) || lp(identity) || lp(caps) || lp(payload).
        val canon = HandshakeTranscript.canonical(a)
        assertArrayEquals(byteArrayOf(0, 0, 0, 2), canon.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 1, 2, 3), canon.copyOfRange(4, 11))
        val emptyCaps = HandshakeTranscript.capabilitiesBytes(Capabilities.getDefaultInstance())
        assertEquals(12, emptyCaps.size)
        assertEquals(4 + 7 + 4 + 4 + (4 + emptyCaps.size) + 4, canon.size)
    }

    @Test
    fun signatureFieldIsNotPartOfTranscript() {
        val unsigned = HandshakeMessage.newBuilder()
            .setStep(3)
            .setStaticPub(ByteString.copyFrom(ByteArray(32) { 7 }))
            .setIdentityPub(ByteString.copyFrom(ByteArray(32) { 9 }))
            .build()
        val signed = unsigned.toBuilder().setSignature(ByteString.copyFrom(ByteArray(64) { 1 })).build()
        assertArrayEquals(HandshakeTranscript.canonical(unsigned), HandshakeTranscript.canonical(signed))
        val t1 = HandshakeTranscript.t1(unsigned)
        val tag = HandshakeTranscript.TAG.toByteArray()
        assertArrayEquals(tag, t1.copyOfRange(0, tag.size))
    }

    @Test
    fun signatureTagsDifferPerRole() {
        val transcript = ByteArray(40) { it.toByte() }
        val initiator = HandshakeTranscript.signatureInput(HandshakeTranscript.Role.INITIATOR, transcript)
        val responder = HandshakeTranscript.signatureInput(HandshakeTranscript.Role.RESPONDER, transcript)
        assertFalse(initiator.contentEquals(responder))
        assertTrue(initiator.contentEquals(HandshakeTranscript.SIG_TAG_INITIATOR.toByteArray() + sha256(transcript)))
        assertTrue(responder.contentEquals(HandshakeTranscript.SIG_TAG_RESPONDER.toByteArray() + sha256(transcript)))
    }

    private fun sha256(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(data)
}
