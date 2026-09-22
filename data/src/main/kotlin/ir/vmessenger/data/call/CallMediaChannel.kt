package ir.vmessenger.data.call

import ir.vmessenger.core.audio.AudioPlaybackEngine
import ir.vmessenger.core.audio.JitterBuffer
import ir.vmessenger.core.audio.JitterFrame
import ir.vmessenger.core.audio.OpusCodec
import ir.vmessenger.core.audio.VoiceAudio
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.network.transport.Connection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * One call's audio over one connection.
 *
 * Every frame is sealed on its own with the per-call key, numbered, and sent without
 * retransmission: a late voice frame is worse than a missing one, so loss is Opus's problem
 * (concealment) rather than the transport's. A frame that fails to authenticate is dropped and
 * nothing else happens — that is also what an attacker sending noise at the port gets.
 *
 * Nothing here can start audio on its own. It is handed an already-derived key and an already-open
 * connection by [CallCoordinator], which only reaches that point through an accept.
 */
internal class CallMediaChannel(
    private val crypto: CryptoEngine,
    private val codec: OpusCodec,
    private val playback: AudioPlaybackEngine,
    private val key: ByteArray,
    private val outbound: MediaDirection,
) {
    private val jitter = JitterBuffer()
    private var sendSequence = 0
    private var reportedBadFrame = false

    /** Runs until the connection closes; cancelling the caller tears the whole call down. */
    suspend fun run(connection: Connection, capture: Flow<ShortArray>, onFirstFrame: suspend () -> Unit) {
        coroutineScope {
            playback.open()
            val transmitting = launch { transmit(connection, capture) }
            val playing = launch { play() }
            try {
                // Returns when the peer's stream ends, which is what ends the call's media.
                receive(connection, onFirstFrame)
            } finally {
                transmitting.cancel()
                playing.cancel()
                playback.close()
            }
        }
    }

    private suspend fun transmit(connection: Connection, capture: Flow<ShortArray>) {
        val packet = ByteArray(VoiceAudio.MAX_PACKET_BYTES)
        capture.collect { pcm ->
            val encoded = codec.encode(pcm, packet)
            if (encoded > 0) {
                val sequence = sendSequence++
                connection.write(CallMediaFrames.frame(sequence, seal(sequence, packet, encoded)))
            }
        }
    }

    private suspend fun receive(connection: Connection, onFirstFrame: suspend () -> Unit) {
        var announced = false
        connection.read().collect { raw ->
            val opened = open(raw)
            if (opened != null) {
                if (!announced) {
                    announced = true
                    onFirstFrame()
                }
                jitter.offer(opened.sequence, opened.payload, opened.payload.size)
            }
        }
    }

    /**
     * Paced by playback, not by a timer: [AudioPlaybackEngine] blocks once its buffer is full, so
     * the loop settles at real time on its own. A concealed frame is written too — silence keeps the
     * stream in step, where skipping it would shift everything after it earlier.
     */
    private suspend fun play() {
        val pcm = ShortArray(VoiceAudio.SAMPLES_PER_FRAME)
        while (currentCoroutineContext().isActive) {
            val samples = when (val next = jitter.poll()) {
                is JitterFrame.Packet -> codec.decode(next.payload, next.length, pcm)
                JitterFrame.Conceal -> codec.decode(null, 0, pcm)
            }
            playback.write(pcm, samples)
        }
    }

    private fun seal(sequence: Int, packet: ByteArray, length: Int): ByteArray =
        crypto.xchacha20Poly1305Seal(
            key,
            CallMediaFrames.nonce(outbound, sequence),
            packet.copyOf(length),
            EMPTY,
        )

    private fun open(raw: ByteArray): Opened? {
        if (raw.size <= CallMediaFrames.SEQUENCE_BYTES) return null
        val sequence = CallMediaFrames.sequenceOf(raw)
        val nonce = CallMediaFrames.nonce(outbound.opposite, sequence)
        val payload = crypto.xchacha20Poly1305Open(key, nonce, CallMediaFrames.payloadOf(raw), EMPTY)
        // Once per call, not once per frame: anyone can aim bytes at the port, and a log line per
        // frame would let them fill the log instead of the call.
        if (payload == null && !reportedBadFrame) {
            reportedBadFrame = true
            AppLogger.warn(TAG, "a media frame failed to authenticate; dropping unauthenticated frames")
        }
        return payload?.let { Opened(sequence, it) }
    }

    private class Opened(val sequence: Int, val payload: ByteArray)

    private companion object {
        const val TAG = "Call"
        val EMPTY = ByteArray(0)
    }
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
