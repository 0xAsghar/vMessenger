package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.LengthPrefixedFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

class LengthPrefixedFramesTest {
    @Test
    fun roundTrip() {
        val payload = "hello vMessenger".toByteArray()
        val encoded = LengthPrefixedFrames.encode(payload)
        val decoded = LengthPrefixedFrames.readFrame(ByteArrayInputStream(encoded))
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun writeReadViaStream() {
        val payload = ByteArray(256) { it.toByte() }
        val out = ByteArrayOutputStream()
        LengthPrefixedFrames.writeFrame(out, payload)
        val decoded = LengthPrefixedFrames.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(payload.size, decoded?.size)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun cleanEndOfStreamReturnsNull() {
        assertNull(LengthPrefixedFrames.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun oversizeRejectedBeforeAllocation() {
        val header = ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()
        val input = CountingInputStream(ByteArrayInputStream(header + ByteArray(16)))
        assertThrows(IllegalArgumentException::class.java) { LengthPrefixedFrames.readFrame(input) }
        assertEquals("only the header may be consumed", 4, input.bytesRead)

        val justOverCap = ByteBuffer.allocate(4).putInt(1025).array()
        val capped = CountingInputStream(ByteArrayInputStream(justOverCap + ByteArray(1025)))
        assertThrows(IllegalArgumentException::class.java) {
            LengthPrefixedFrames.readFrame(capped, maxSize = 1024)
        }
        assertEquals(4, capped.bytesRead)

        val negative = ByteBuffer.allocate(4).putInt(-1).array()
        assertThrows(IllegalArgumentException::class.java) {
            LengthPrefixedFrames.readFrame(ByteArrayInputStream(negative))
        }
    }

    @Test
    fun truncatedThrows() {
        val encoded = LengthPrefixedFrames.encode(ByteArray(1000) { 7 })
        val truncatedBody = encoded.copyOf(encoded.size - 1)
        assertThrows(EOFException::class.java) {
            LengthPrefixedFrames.readFrame(ByteArrayInputStream(truncatedBody))
        }
        val truncatedHeader = encoded.copyOf(2)
        assertThrows(EOFException::class.java) {
            LengthPrefixedFrames.readFrame(ByteArrayInputStream(truncatedHeader))
        }
    }

    @Test
    fun chunkedReadRoundTrip() {
        val payload = ByteArray(3 * LengthPrefixedFrames.CHUNK_SIZE + 12_345) { (it * 31).toByte() }
        val encoded = LengthPrefixedFrames.encode(payload)
        // Trickle the bytes in small pieces so every chunk needs several reads.
        val input = TrickleInputStream(ByteArrayInputStream(encoded), maxPerRead = 1_000)
        val decoded = LengthPrefixedFrames.readFrame(input)
        assertArrayEquals(payload, decoded)
        assertTrue(input.reads > payload.size / 1_000)
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int = inner.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) bytesRead += it }
    }

    private class TrickleInputStream(private val inner: InputStream, private val maxPerRead: Int) : InputStream() {
        var reads = 0
            private set

        override fun read(): Int = inner.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            return inner.read(b, off, minOf(len, maxPerRead))
        }
    }
}
