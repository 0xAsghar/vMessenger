package ir.vmessenger.core.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EndpointRecordTranscriptTest {
    private val hash = ByteArray(32)
    private val pub = ByteArray(32) { 1 }
    private val entries = listOf(
        EndpointRecordTranscript.Entry("relay", "wss://b"),
        EndpointRecordTranscript.Entry("internet", "1.2.3.4:1"),
        EndpointRecordTranscript.Entry("relay", "wss://a"),
    )

    @Test
    fun v2SortsEntriesAndIsOrderIndependent() {
        val shuffled = listOf(entries[1], entries[2], entries[0])
        val a = EndpointRecordTranscript.buildV2(hash, pub, entries, 1, 2, 3)
        val b = EndpointRecordTranscript.buildV2(hash, pub, shuffled, 1, 2, 3)
        assertArrayEquals(a, b)
    }

    @Test
    fun v2LayoutIsTaggedAndLengthPrefixed() {
        val transcript = EndpointRecordTranscript.buildV2(hash, pub, entries.take(1), 1, 2, 3)
        val tag = "vmessenger-endpoint-record-v2".toByteArray()
        assertArrayEquals(tag, transcript.copyOf(tag.size))
        val expectedSize = tag.size + (4 + 32) + (4 + 32) + 4 + (4 + 5) + (4 + 7) + 8 + 8 + 8
        assertEquals(expectedSize, transcript.size)
        assertArrayEquals(Canonical.u32be(1), transcript.copyOfRange(tag.size + 72, tag.size + 76))
        assertArrayEquals(Canonical.u64be(3), transcript.copyOfRange(transcript.size - 8, transcript.size))
    }

    @Test
    fun v2DiffersFromLegacyAndLegacyIsUnchanged() {
        val v2 = EndpointRecordTranscript.buildV2(hash, pub, entries, 1, 2, 3)
        val legacy = EndpointRecordTranscript.buildLegacy(hash, pub, entries, 1, 2, 3)
        assertFalse(v2.contentEquals(legacy))
        val expectedLegacy = hash + pub +
            "internet\t1.2.3.4:1\nrelay\twss://a\nrelay\twss://b".toByteArray() +
            "1".toByteArray() + "2".toByteArray() + "3".toByteArray()
        assertArrayEquals(expectedLegacy, legacy)
    }
}
