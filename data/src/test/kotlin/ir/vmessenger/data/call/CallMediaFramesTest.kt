package ir.vmessenger.data.call

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CallMediaFramesTest {
    @Test
    fun `the two directions never share a nonce`() {
        // The property that matters: both ends seal with the same per-call key, so if caller and
        // callee ever produced the same nonce for the same sequence number, an eavesdropper would
        // get the XOR of two plaintexts. Checked across a wide span of sequence numbers, not one.
        val sequences = listOf(0, 1, 2, 50, 3_000, 150_000, Int.MAX_VALUE)
        sequences.forEach { sequence ->
            val fromCaller = CallMediaFrames.nonce(MediaDirection.CallerToCallee, sequence)
            val fromCallee = CallMediaFrames.nonce(MediaDirection.CalleeToCaller, sequence)
            assertNotEquals(
                fromCaller.toList(),
                fromCallee.toList(),
                "directions collided at sequence $sequence",
            )
        }
    }

    @Test
    fun `a nonce is unique per sequence within one direction`() {
        val seen = mutableSetOf<List<Byte>>()
        repeat(SAMPLE_FRAMES) { sequence ->
            val nonce = CallMediaFrames.nonce(MediaDirection.CallerToCallee, sequence).toList()
            assertTrue(seen.add(nonce), "nonce repeated at sequence $sequence")
        }
    }

    @Test
    fun `a nonce is the size XChaCha20 requires`() {
        assertEquals(24, CallMediaFrames.nonce(MediaDirection.CallerToCallee, 7).size)
    }

    @Test
    fun `the direction lives in the first byte and the sequence in the last four`() {
        val nonce = CallMediaFrames.nonce(MediaDirection.CalleeToCaller, 0x01020304)
        assertEquals(1, nonce[0], "direction tag")
        assertContentEquals(ByteArray(15), nonce.copyOfRange(1, 16), "the middle stays zero")
        assertContentEquals(byteArrayOf(1, 2, 3, 4), nonce.copyOfRange(20, 24), "sequence, big-endian")
    }

    @Test
    fun `each direction is its own opposite's opposite`() {
        assertEquals(MediaDirection.CalleeToCaller, MediaDirection.CallerToCallee.opposite)
        assertEquals(MediaDirection.CallerToCallee, MediaDirection.CalleeToCaller.opposite)
    }

    @Test
    fun `a framed sequence survives the round trip`() {
        // Including values whose top bit is set: read back with a sign-extending shift instead of a
        // masked one, 0xFFFFFFFF would come back as -1 and desynchronise the jitter buffer.
        listOf(0, 1, 127, 128, 255, 256, 65_535, 1_000_000, Int.MAX_VALUE, -1).forEach { sequence ->
            val framed = CallMediaFrames.frame(sequence, byteArrayOf(9, 9))
            assertEquals(sequence, CallMediaFrames.sequenceOf(framed), "sequence $sequence")
        }
    }

    @Test
    fun `a frame is its payload behind four bytes of sequence`() {
        val payload = ByteArray(PAYLOAD_SIZE) { it.toByte() }
        val framed = CallMediaFrames.frame(42, payload)
        assertEquals(CallMediaFrames.SEQUENCE_BYTES + PAYLOAD_SIZE, framed.size)
        assertContentEquals(payload, CallMediaFrames.payloadOf(framed))
    }

    private companion object {
        /** Twenty seconds of call at 20 ms a frame — enough to catch a truncated sequence field. */
        const val SAMPLE_FRAMES = 1_000
        const val PAYLOAD_SIZE = 60
    }
}
