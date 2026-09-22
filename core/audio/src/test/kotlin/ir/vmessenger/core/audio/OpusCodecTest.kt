package ir.vmessenger.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the codec actually works, not merely that the dependency resolves.
 *
 * It runs as a plain JVM test because the implementation is pure Java — which is itself half the
 * reason for choosing it over a JNI build: the audio path is testable off-device.
 */
class OpusCodecTest {

    private val codec: OpusCodec = ConcentusOpusCodecFactory().create()

    @Test
    fun `a spoken-range tone survives a round trip`() {
        val source = tone(frequencyHz = 440.0)
        val packet = ByteArray(VoiceAudio.MAX_PACKET_BYTES)

        val encoded = codec.encode(source, packet)
        val decoded = ShortArray(VoiceAudio.SAMPLES_PER_FRAME)
        val samples = codec.decode(packet, encoded, decoded)

        assertEquals(VoiceAudio.SAMPLES_PER_FRAME, samples)
        // Opus is lossy, so the test is about energy rather than samples: a decoded frame that is
        // silent would mean the codec ran and produced nothing, which is the failure worth catching.
        assertTrue(energy(decoded) > energy(source) / ENERGY_TOLERANCE, "decoded frame was near-silent")
    }

    @Test
    fun `an encoded voice frame is far smaller than the raw one`() {
        val packet = ByteArray(VoiceAudio.MAX_PACKET_BYTES)

        val encoded = codec.encode(tone(frequencyHz = 300.0), packet)

        assertTrue(encoded > 0, "nothing was encoded")
        // Raw is two bytes a sample; anything near that means the encoder is not really running.
        assertTrue(encoded < VoiceAudio.SAMPLES_PER_FRAME, "frame did not compress: $encoded bytes")
    }

    @Test
    fun `a lost packet is concealed rather than thrown`() {
        // Prime the decoder, so concealment has a previous frame to continue from.
        val packet = ByteArray(VoiceAudio.MAX_PACKET_BYTES)
        val primed = codec.encode(tone(frequencyHz = 440.0), packet)
        codec.decode(packet, primed, ShortArray(VoiceAudio.SAMPLES_PER_FRAME))

        val concealed = ShortArray(VoiceAudio.SAMPLES_PER_FRAME)
        val samples = codec.decode(null, 0, concealed)

        // A full frame either way: concealment invents one, and the fallback fills silence. What
        // must never happen is a short frame, which would desynchronise playback.
        assertEquals(VoiceAudio.SAMPLES_PER_FRAME, samples)
    }

    private fun tone(frequencyHz: Double): ShortArray = ShortArray(VoiceAudio.SAMPLES_PER_FRAME) { index ->
        val angle = 2.0 * PI * frequencyHz * index / VoiceAudio.SAMPLE_RATE
        (sin(angle) * AMPLITUDE).toInt().toShort()
    }

    private fun energy(frame: ShortArray): Long = frame.sumOf { abs(it.toLong()) }

    private companion object {
        const val AMPLITUDE = 8_000
        const val ENERGY_TOLERANCE = 4
    }
}
