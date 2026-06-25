package ir.vmessenger.core.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RelayProofTest {
    @Test
    fun listenerProofTranscriptIsDeterministic() {
        val id = ByteArray(32) { it.toByte() }
        val ts = 1_700_000_000_000L
        val a = RelayProof.buildListenerProofTranscript(id, ts)
        val b = RelayProof.buildListenerProofTranscript(id, ts)
        assertArrayEquals(a, b)
    }
}
