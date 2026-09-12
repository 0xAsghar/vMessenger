package ir.vmessenger.feature.chat.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import ir.vmessenger.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

private const val TAG = "VoiceRecorder"

private const val VOICE_DIR = "voice"
private const val FILE_SUFFIX = ".m4a"

// Voice, not music: 16 kHz mono AAC at 32 kbps is intelligible and keeps a five-minute
// message around 1 MB, which matters on a link the app pays for chunk by chunk.
private const val SAMPLE_RATE_HZ = 16_000
private const val BIT_RATE = 32_000
private const val CHANNEL_COUNT = 1

private const val POLL_INTERVAL_MS = 50L

/** `getMaxAmplitude()` is a 16-bit magnitude; the UI wants a 0..1 level. */
private const val MAX_RAW_AMPLITUDE = 32_767f

/** Bars in the stored waveform, and the value range of one bar (an unsigned byte). */
internal const val WAVEFORM_BUCKETS = 64
private const val MAX_BAR_LEVEL = 255

/** Shorter than this the press was a mis-tap on the mic, not a message. */
internal const val MIN_RECORDING_MS = 700L

/** Hard ceiling; the recorder stops itself and reports the message as ready to send. */
internal const val MAX_RECORDING_MS = 5 * 60 * 1000L

/** What the composer shows while a recording is in flight. */
@Immutable
data class VoiceRecorderState(
    val recording: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
)

/** A finished recording, ready for `SendVoiceUseCase`. */
class VoiceRecording(
    val filePath: String,
    val durationMs: Long,
    val waveform: ByteArray,
)

/**
 * Records one voice message into the cache and hands back the file, its length and a
 * [WAVEFORM_BUCKETS]-bar waveform.
 *
 * Neither a composable nor an injected singleton: the screen owns one and drives it from the
 * mic gesture. [scope] belongs to the caller (a `viewModelScope`), so a screen that goes away
 * takes the amplitude poller with it.
 */
class VoiceRecorder(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(VoiceRecorderState())
    val state: StateFlow<VoiceRecorderState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L
    private var pollJob: Job? = null
    private val samples = ArrayList<Int>()

    /** True between a successful [start] and the following [stop] or [cancel]. */
    val isRecording: Boolean get() = recorder != null

    /**
     * Opens the microphone. Returns false when the encoder refuses to start — another app holds
     * the mic, or the permission was revoked mid-gesture — and leaves no file behind.
     * [onMaxDuration] fires with the finished message once [MAX_RECORDING_MS] is reached.
     */
    fun start(onMaxDuration: (VoiceRecording) -> Unit = {}): Boolean {
        if (recorder != null) return false
        val file = newOutputFile()
        val media = runCatching {
            newRecorder().also { media ->
                media.configureForVoice(file)
                media.prepare()
                media.start()
            }
        }.getOrElse { error ->
            AppLogger.warn(TAG, "microphone unavailable: ${error.javaClass.simpleName}")
            file.delete()
            null
        }
        media?.let { began(it, file, onMaxDuration) }
        return media != null
    }

    /**
     * Closes the file and hands it over, or returns null for a press shorter than
     * [MIN_RECORDING_MS] — the file is deleted in that case, so a mis-tap leaves nothing.
     */
    fun stop(): VoiceRecording? {
        val file = outputFile
        outputFile = null
        val elapsed = finish()
        if (file == null) return null
        return if (elapsed >= MIN_RECORDING_MS) {
            VoiceRecording(filePath = file.absolutePath, durationMs = elapsed, waveform = waveformOf(samples))
        } else {
            file.delete()
            null
        }
    }

    /** Abandons the recording: the encoder is released and the file never reaches the caller. */
    fun cancel() {
        val file = outputFile
        outputFile = null
        finish()
        file?.delete()
    }

    private fun began(media: MediaRecorder, file: File, onMaxDuration: (VoiceRecording) -> Unit) {
        recorder = media
        outputFile = file
        startedAtMs = SystemClock.elapsedRealtime()
        samples.clear()
        _state.value = VoiceRecorderState(recording = true)
        pollJob = scope.launch { poll(onMaxDuration) }
    }

    /** Drives both the live level and the stored waveform off one amplitude reading. */
    private suspend fun poll(onMaxDuration: (VoiceRecording) -> Unit) {
        while (coroutineContext.isActive) {
            delay(POLL_INTERVAL_MS)
            val media = recorder ?: return
            val raw = runCatching { media.maxAmplitude }.getOrDefault(0)
            samples += raw
            val elapsed = SystemClock.elapsedRealtime() - startedAtMs
            _state.value = VoiceRecorderState(
                recording = true,
                elapsedMs = elapsed,
                amplitude = amplitudeLevel(raw),
            )
            if (elapsed >= MAX_RECORDING_MS) {
                stop()?.let(onMaxDuration)
                return
            }
        }
    }

    /** Releases the encoder and returns the recorded length, or -1 when it produced nothing. */
    private fun finish(): Long {
        pollJob?.cancel()
        pollJob = null
        val media = recorder ?: return -1L
        recorder = null
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        // stop() throws when the encoder never got a full frame, which is exactly the mis-tap
        // case; treating it as "no recording" keeps that out of the conversation.
        val stopped = runCatching { media.stop() }.isSuccess
        runCatching { media.release() }
        _state.value = VoiceRecorderState()
        return if (stopped) elapsed else -1L
    }

    private fun newOutputFile(): File {
        val dir = File(appContext.cacheDir, VOICE_DIR)
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}$FILE_SUFFIX")
    }

    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(appContext)
    } else {
        // The no-argument constructor is the only one below API 31, and minSdk is 26.
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
}

private fun MediaRecorder.configureForVoice(file: File) {
    setAudioSource(MediaRecorder.AudioSource.MIC)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    setAudioSamplingRate(SAMPLE_RATE_HZ)
    setAudioChannels(CHANNEL_COUNT)
    setAudioEncodingBitRate(BIT_RATE)
    setOutputFile(file.absolutePath)
}

/** One amplitude reading as the 0..1 level the recording bar animates. */
internal fun amplitudeLevel(raw: Int): Float = (raw / MAX_RAW_AMPLITUDE).coerceIn(0f, 1f)

/**
 * Folds the amplitude samples into [WAVEFORM_BUCKETS] unsigned bytes: each bar is the mean of
 * the samples that fall in it, scaled by the loudest sample of the recording rather than by the
 * encoder's full range — a whispered message should still draw bars, not a flat line.
 *
 * Fewer samples than buckets (a very short recording) repeats samples across bars instead of
 * leaving gaps, which is why the ranges are computed rather than stepped.
 */
internal fun waveformOf(samples: List<Int>): ByteArray {
    val bars = ByteArray(WAVEFORM_BUCKETS)
    val peak = samples.maxOrNull() ?: 0
    if (peak <= 0) return bars
    for (bucket in 0 until WAVEFORM_BUCKETS) {
        val from = bucket * samples.size / WAVEFORM_BUCKETS
        val until = ((bucket + 1) * samples.size / WAVEFORM_BUCKETS)
            .coerceIn(from + 1, samples.size)
        var sum = 0L
        for (index in from until until) {
            sum += samples[index].coerceAtLeast(0)
        }
        val mean = sum / (until - from)
        bars[bucket] = (mean * MAX_BAR_LEVEL / peak).toInt().coerceIn(0, MAX_BAR_LEVEL).toByte()
    }
    return bars
}
