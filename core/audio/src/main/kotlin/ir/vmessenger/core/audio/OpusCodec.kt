package ir.vmessenger.core.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import ir.vmessenger.core.common.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One call's worth of Opus, both directions.
 *
 * An interface for one reason: the implementation behind it is pure Java, chosen so the app does not
 * ship a JNI codec in the process that holds the message keys, and that trade is CPU for safety. If
 * it ever costs too much battery, a libopus-backed implementation goes here and nothing else moves.
 */
interface OpusCodec : AutoCloseable {
    /** Encodes one [VoiceAudio.SAMPLES_PER_FRAME] frame into [packet]; returns the bytes written. */
    fun encode(pcm: ShortArray, packet: ByteArray): Int

    /**
     * Decodes one packet into [pcm] and returns the samples written.
     *
     * A null [packet] asks for packet-loss concealment: Opus invents a plausible continuation of the
     * previous frame, which is what keeps a dropped packet sounding like a blur instead of a click.
     */
    fun decode(packet: ByteArray?, length: Int, pcm: ShortArray): Int
}

/** Builds a codec per call, so two calls never share encoder state. */
interface OpusCodecFactory {
    fun create(): OpusCodec
}

@Singleton
class ConcentusOpusCodecFactory @Inject constructor() : OpusCodecFactory {
    override fun create(): OpusCodec = ConcentusOpusCodec()
}

private const val COMPLEXITY = 5
private const val EXPECTED_LOSS_PERCENT = 5

internal class ConcentusOpusCodec : OpusCodec {
    private val encoder = OpusEncoder(
        VoiceAudio.SAMPLE_RATE,
        VoiceAudio.CHANNELS,
        OpusApplication.OPUS_APPLICATION_VOIP,
    ).apply {
        setBitrate(VoiceAudio.BITRATE)
        // Silence costs almost nothing to send, which matters on a metered connection and on a
        // relay that pays for every byte of a call it cannot read.
        setUseDTX(true)
        setUseVBR(true)
        // Mid complexity: a pure-Java encoder runs on a phone core every 20 ms, and the quality
        // above this is not worth the frames it would cost on a slow device.
        setComplexity(COMPLEXITY)
        // Redundancy for the next frame, sized for a loss rate we expect rather than a perfect link.
        setUseInbandFEC(true)
        setPacketLossPercent(EXPECTED_LOSS_PERCENT)
    }

    private val decoder = OpusDecoder(VoiceAudio.SAMPLE_RATE, VoiceAudio.CHANNELS)

    override fun encode(pcm: ShortArray, packet: ByteArray): Int =
        encoder.encode(pcm, 0, VoiceAudio.SAMPLES_PER_FRAME, packet, 0, packet.size)

    override fun decode(packet: ByteArray?, length: Int, pcm: ShortArray): Int = try {
        decoder.decode(packet, 0, length, pcm, 0, VoiceAudio.SAMPLES_PER_FRAME, false)
    } catch (e: OpusException) {
        // A codec fault must not take the call down: a frame of silence is a worse sound than
        // concealment and a better outcome than a dropped call.
        pcm.fill(0, 0, VoiceAudio.SAMPLES_PER_FRAME)
        AppLogger.warn(TAG, "opus decode failed: ${e.message}")
        VoiceAudio.SAMPLES_PER_FRAME
    }

    override fun close() = Unit

    private companion object {
        const val TAG = "Call"
    }
}
