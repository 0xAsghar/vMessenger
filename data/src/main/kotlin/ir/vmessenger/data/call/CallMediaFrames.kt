package ir.vmessenger.data.call

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which way a frame travels.
 *
 * It picks the nonce prefix, and that is the whole point: both ends seal with the same per-call key,
 * so without something distinguishing the directions each sequence number would be used twice under
 * one key — which for a stream cipher is the one mistake that loses the plaintext.
 */
internal enum class MediaDirection(val tag: Byte) {
    CallerToCallee(0),
    CalleeToCaller(1),
    ;

    val opposite: MediaDirection get() = if (this == CallerToCallee) CalleeToCaller else CallerToCallee
}

/**
 * Seals and opens one call's frames under the per-call key.
 *
 * The send sequence belongs to the call, not to a connection. The nonce is (direction, sequence), so
 * a second path that counted from zero again — a relay circuit after a direct socket, a path opened
 * after the last one died — would repeat nonces under the same key. Every path a call ever uses
 * draws from this one counter.
 *
 * A frame with an empty payload is a *greeting*: it says nothing except that the path it arrived on
 * reaches someone holding the key, which is exactly what deciding between paths needs.
 */
internal class MediaSealer(
    private val crypto: CryptoEngine,
    private val key: ByteArray,
    private val outbound: MediaDirection,
) {
    private val sequence = AtomicInteger()

    @Volatile
    private var reportedBadFrame = false

    /** The next frame, carrying the first [length] bytes of [packet]; nothing at all is a greeting. */
    fun seal(packet: ByteArray = EMPTY, length: Int = 0): ByteArray {
        val next = sequence.getAndIncrement()
        val sealed = crypto.xchacha20Poly1305Seal(
            key,
            CallMediaFrames.nonce(outbound, next),
            packet.copyOf(length),
            EMPTY,
        )
        return CallMediaFrames.frame(next, sealed)
    }

    /** The frame's contents if it authenticates as the other end's under this call's key; else null. */
    fun open(raw: ByteArray): OpenedFrame? {
        if (raw.size <= CallMediaFrames.SEQUENCE_BYTES) return null
        val sequence = CallMediaFrames.sequenceOf(raw)
        val nonce = CallMediaFrames.nonce(outbound.opposite, sequence)
        val payload = crypto.xchacha20Poly1305Open(key, nonce, CallMediaFrames.payloadOf(raw), EMPTY)
        // Once per call, not once per frame: anyone can aim bytes at a media port, and a log line per
        // frame would let them fill the log instead of the call.
        if (payload == null && !reportedBadFrame) {
            reportedBadFrame = true
            AppLogger.warn(TAG, "a media frame failed to authenticate; dropping unauthenticated frames")
        }
        return payload?.let { OpenedFrame(sequence, it) }
    }

    /** Zeroes the key. Called once nothing can seal or open with it any more. */
    fun wipe() = key.fill(0)

    private companion object {
        const val TAG = "Call"
        val EMPTY = ByteArray(0)
    }
}

/** One authenticated frame: its place in the stream, and the Opus packet it carries (if any). */
internal class OpenedFrame(val sequence: Int, val payload: ByteArray) {
    val greeting: Boolean get() = payload.isEmpty()
}

/**
 * The wire layout of a media frame, kept apart from the loops that use it so the part that has to
 * be exactly right can be tested exactly.
 *
 * A frame is `[4-byte big-endian sequence][sealed Opus packet]`. The sequence travels in the clear
 * because the receiver needs it to build the nonce before it can authenticate anything — it reveals
 * only how many frames have gone by, which the frame count already reveals.
 */
internal object CallMediaFrames {
    const val SEQUENCE_BYTES = 4
    const val NONCE_BYTES = 24

    fun frame(sequence: Int, sealed: ByteArray): ByteArray =
        ByteArray(SEQUENCE_BYTES + sealed.size).also { out ->
            writeSequence(sequence, out, 0)
            sealed.copyInto(out, SEQUENCE_BYTES)
        }

    fun sequenceOf(raw: ByteArray): Int =
        (raw[0].toInt() and 0xFF shl 24) or
            (raw[1].toInt() and 0xFF shl 16) or
            (raw[2].toInt() and 0xFF shl 8) or
            (raw[3].toInt() and 0xFF)

    fun payloadOf(raw: ByteArray): ByteArray = raw.copyOfRange(SEQUENCE_BYTES, raw.size)

    /**
     * Direction in the first byte, sequence in the last four, zero in between.
     *
     * Both ends hold the same key, so the direction byte is the only thing keeping the two streams
     * from using identical nonces — which for a stream cipher means XOR-ing two plaintexts together
     * and handing an eavesdropper both. It is one byte, and it is the whole defence.
     */
    fun nonce(direction: MediaDirection, sequence: Int): ByteArray =
        ByteArray(NONCE_BYTES).also { out ->
            out[0] = direction.tag
            writeSequence(sequence, out, NONCE_BYTES - SEQUENCE_BYTES)
        }

    private fun writeSequence(sequence: Int, out: ByteArray, offset: Int) {
        out[offset] = (sequence ushr 24).toByte()
        out[offset + 1] = (sequence ushr 16).toByte()
        out[offset + 2] = (sequence ushr 8).toByte()
        out[offset + 3] = sequence.toByte()
    }
}
