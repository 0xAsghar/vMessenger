package ir.vmessenger.core.audio

/**
 * The one audio shape a call uses, fixed at both ends.
 *
 * 48 kHz mono in 20 ms frames is Opus's native voice configuration, and fixing it means no
 * negotiation and no resampling: a frame is always [SAMPLES_PER_FRAME] shorts, which is what the
 * capture buffer, the codec, the jitter buffer and the playback buffer all size themselves against.
 *
 * 20 ms is the usual trade: shorter frames cut latency but spend proportionally more on packet
 * headers, and at 50 packets a second the overhead already dominates a 24 kbit/s stream.
 */
object VoiceAudio {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val FRAME_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE / 1_000 * FRAME_MS

    /** Target bitrate. Opus with DTX drops far below this while nobody is speaking. */
    const val BITRATE = 24_000

    /**
     * Upper bound on one encoded frame. Opus voice frames at this bitrate land near 60–80 bytes;
     * the buffer is sized for the worst case so encoding never has to grow one.
     */
    const val MAX_PACKET_BYTES = 400
}
