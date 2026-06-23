package ir.vmessenger.core.common.network

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object LengthPrefixedFrames {
    const val MAX_FRAME_SIZE = 1 * 1024 * 1024

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_SIZE) { "Frame too large" }
        return ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(header, read, 4 - read)
            if (n < 0) return null
            read += n
        }
        val length = ByteBuffer.wrap(header).int
        require(length in 0..MAX_FRAME_SIZE) { "Invalid frame length: $length" }
        val payload = ByteArray(length)
        read = 0
        while (read < length) {
            val n = input.read(payload, read, length - read)
            if (n < 0) error("Unexpected end of stream")
            read += n
        }
        return payload
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        output.write(encode(payload))
        output.flush()
    }
}
