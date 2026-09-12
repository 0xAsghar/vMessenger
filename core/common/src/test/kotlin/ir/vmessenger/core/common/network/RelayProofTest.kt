package ir.vmessenger.core.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayProofTest {
    private val id = ByteArray(32) { it.toByte() }
    private val pub = ByteArray(32) { (0x80 + it).toByte() }
    private val ts = 1_700_000_000_000L

    @Test
    fun listenerProofTranscriptIsDeterministic() {
        val a = RelayProof.buildListenerProofTranscript(id, pub, ts)
        val b = RelayProof.buildListenerProofTranscript(id, pub, ts)
        assertArrayEquals(a, b)
    }

    @Test
    fun v2TranscriptLayout() {
        val transcript = RelayProof.buildListenerProofTranscript(id, pub, ts)
        val tag = "vmessenger-relay-listener-v2".toByteArray()
        assertEquals(tag.size + 4 + 32 + 4 + 32 + 8, transcript.size)
        assertArrayEquals(tag, transcript.copyOf(tag.size))
        assertArrayEquals(Canonical.u32be(32), transcript.copyOfRange(tag.size, tag.size + 4))
        assertArrayEquals(id, transcript.copyOfRange(tag.size + 4, tag.size + 36))
        assertArrayEquals(pub, transcript.copyOfRange(tag.size + 40, tag.size + 72))
        assertArrayEquals(Canonical.u64be(ts), transcript.copyOfRange(transcript.size - 8, transcript.size))
    }

    @Test
    fun v2TranscriptBindsIdentityPubAndDiffersFromLegacy() {
        val a = RelayProof.buildListenerProofTranscript(id, pub, ts)
        val otherPub = RelayProof.buildListenerProofTranscript(id, ByteArray(32) { 9 }, ts)
        assertFalse(a.contentEquals(otherPub))
        assertFalse(a.contentEquals(RelayProof.buildLegacyListenerProofTranscript(id, ts)))
    }

    @Test
    fun legacyTranscriptIsUnchanged() {
        val expected = "vmessenger-relay-listener".toByteArray() + id + ts.toString().toByteArray()
        assertArrayEquals(expected, RelayProof.buildLegacyListenerProofTranscript(id, ts))
    }
}
