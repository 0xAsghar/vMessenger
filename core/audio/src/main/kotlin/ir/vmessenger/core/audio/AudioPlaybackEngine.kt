package ir.vmessenger.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import ir.vmessenger.core.common.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The earpiece or speaker, written one 20 ms frame at a time.
 *
 * `USAGE_VOICE_COMMUNICATION` is what makes this a call rather than media: it is the usage the
 * platform routes to the earpiece, ducks other audio for, and lets the volume keys control as call
 * volume. Declaring it media would play a call through the speaker at music volume.
 *
 * [write] is deliberately forgiving — a call that cannot play a frame should lose that frame, not
 * end — so failures are logged and swallowed rather than thrown at the media loop.
 */
@Singleton
class AudioPlaybackEngine @Inject constructor() {
    private var track: AudioTrack? = null

    fun open() {
        if (track != null) return
        track = runCatching { buildTrack() }
            .onFailure { AppLogger.warn(TAG, "playback unavailable: ${it.message}") }
            .getOrNull()
            ?.also { it.play() }
    }

    fun write(pcm: ShortArray, samples: Int) {
        val active = track ?: return
        runCatching { active.write(pcm, 0, samples) }
            .onFailure { AppLogger.warn(TAG, "playback write failed: ${it.message}") }
    }

    fun close() {
        val active = track ?: return
        track = null
        runCatching { active.pause() }
        runCatching { active.flush() }
        runCatching { active.release() }
    }

    private fun buildTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bytesPerFrame = VoiceAudio.SAMPLES_PER_FRAME * Short.SIZE_BYTES
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(VoiceAudio.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, bytesPerFrame * BUFFER_FRAMES))
            .build()
    }

    private companion object {
        const val TAG = "Call"
        const val BUFFER_FRAMES = 4
    }
}
