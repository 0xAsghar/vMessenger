package ir.vmessenger.network.transport

import ir.vmessenger.core.common.network.LengthPrefixedFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
}
