package ir.vmessenger.core.common.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * `u32be(length) || payload` framing shared by the TCP and UDP transports.
 * [readFrame] validates the announced length against [maxSize] *before*
 * allocating anything and then reads in [CHUNK_SIZE] pieces, so a hostile
 * header can neither trigger a giant allocation nor pin a buffer that is never
 * filled.
 */
object LengthPrefixedFrames {
    const val MAX_FRAME_SIZE = 1 * 1024 * 1024
    const val CHUNK_SIZE = 64 * 1024
    private const val HEADER_SIZE = 4

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_SIZE) { "Frame too large" }
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    /**
     * Reads one frame, or returns null on a clean end of stream before any
     * header byte. Throws [IllegalArgumentException] for a length outside
     * `0..maxSize` and [EOFException] when the stream ends mid-frame.
     */
    fun readFrame(input: InputStream, maxSize: Int = MAX_FRAME_SIZE): ByteArray? {
        val length = readLength(input) ?: return null
        require(length in 0..maxSize) { "Invalid frame length: $length (max $maxSize)" }
        return readPayload(input, length)
    }

    /** The announced payload length, or null on a clean end of stream before any header byte. */
    private fun readLength(input: InputStream): Int? {
        val header = ByteArray(HEADER_SIZE)
        var read = 0
        while (read < HEADER_SIZE) {
            val n = input.read(header, read, HEADER_SIZE - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException("Unexpected end of stream in frame header")
            }
            read += n
        }
        return ByteBuffer.wrap(header).int
    }

    private fun readPayload(input: InputStream, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val chunk = ByteArray(minOf(length, CHUNK_SIZE))
        val out = ByteArrayOutputStream(chunk.size)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(chunk, 0, minOf(remaining, chunk.size))
            if (n < 0) throw EOFException("Unexpected end of stream: $remaining of $length bytes missing")
            out.write(chunk, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        output.write(encode(payload))
        output.flush()
    }
}
