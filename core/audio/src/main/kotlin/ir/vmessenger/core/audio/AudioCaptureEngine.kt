package ir.vmessenger.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import ir.vmessenger.core.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The microphone, as a cold stream of 20 ms frames.
 *
 * `VOICE_COMMUNICATION` rather than `MIC`: it is the source the platform attaches its echo
 * canceller to, and without a reference signal a speakerphone call feeds itself back. The three
 * effects are then requested explicitly, because availability is per-device and asking for one that
 * is not there throws rather than degrading.
 *
 * The stream is cold and tied to its collector: cancelling the collection is what releases the
 * microphone, so a call that ends — however it ends — cannot leave it open.
 */
@Singleton
class AudioCaptureEngine @Inject constructor() {
    private val effects = mutableListOf<AudioEffect>()

    /**
     * Silences the stream without releasing the microphone.
     *
     * Deliberate, and worth being plain about: muting stops the other side hearing anything, and
     * Android still shows the microphone as in use, because it is. Frames keep flowing so timing
     * and the jitter buffer do not have to be restarted on unmute; DTX makes silence nearly free.
     */
    @Volatile
    var muted: Boolean = false

    fun frames(): Flow<ShortArray> = flow {
        val recorder = openRecorder() ?: return@flow
        try {
            attachEffects(recorder.audioSessionId)
            recorder.startRecording()
            val frame = ShortArray(VoiceAudio.SAMPLES_PER_FRAME)
            val silence = ShortArray(VoiceAudio.SAMPLES_PER_FRAME)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(frame, 0, frame.size)
                // A short read is a stopping recorder, not a partial frame worth sending.
                if (read < frame.size) break
                emit(if (muted) silence else frame.copyOf())
            }
        } finally {
            release(recorder)
        }
    }.flowOn(Dispatchers.Default)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before a call is answered.
    private fun openRecorder(): AudioRecord? = runCatching {
        val minimum = AudioRecord.getMinBufferSize(
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Several frames of slack: sized off one frame, a scheduling hiccup becomes a dropout.
        val bytesPerFrame = VoiceAudio.SAMPLES_PER_FRAME * Short.SIZE_BYTES
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            VoiceAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, bytesPerFrame * BUFFER_FRAMES),
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }.onFailure { AppLogger.warn(TAG, "microphone unavailable: ${it.message}") }.getOrNull()

    private fun attachEffects(sessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let { effects += it.also { fx -> fx.enabled = true } }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let { effects += it.also { fx -> fx.enabled = true } }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let { effects += it.also { fx -> fx.enabled = true } }
            }
        }.onFailure { AppLogger.warn(TAG, "audio effects unavailable: ${it.message}") }
    }

    private fun release(recorder: AudioRecord) {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        effects.forEach { effect -> runCatching { effect.release() } }
        effects.clear()
    }

    private companion object {
        const val TAG = "Call"
        const val BUFFER_FRAMES = 4
    }
}
