package ir.vmessenger.feature.chat.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoicePlayback"

/** How often the position is republished while playing; fast enough for a 64-bar waveform. */
private const val TICK_INTERVAL_MS = 100L

/** Playback speeds, in the order the speed chip cycles through them. */
enum class VoiceSpeed(val factor: Float) {
    NORMAL(1f),
    FAST(1.5f),
    FASTEST(2f),
    ;

    /** The next speed in the cycle, wrapping back to [NORMAL]. */
    fun next(): VoiceSpeed = entries[(ordinal + 1) % entries.size]
}

/** The one voice message currently loaded in the shared player. */
@Immutable
data class VoicePlayback(
    val messageId: String? = null,
    val playing: Boolean = false,
    val progress: Float = 0f,
    val elapsedMs: Long = 0L,
    val speed: VoiceSpeed = VoiceSpeed.NORMAL,
)

/**
 * What may follow the message being played. [upNext] is the caller's ordered list of unplayed
 * voice messages — a list that does not contain the finished one is simply played from its
 * head. [openTrack] decrypts a candidate into a temp file when its turn comes, or returns null
 * to end the run. Keeping both on the caller's side is what leaves this controller unaware of
 * the database.
 */
class VoiceQueue(
    val upNext: List<String>,
    val openTrack: suspend (String) -> String?,
)

/**
 * The app's single voice player. It is a singleton because two voice messages playing at once
 * is never what anyone meant: starting one stops whatever was playing.
 *
 * The caller hands over a *decrypted* temp file; it belongs to the player from that moment and
 * is deleted as soon as playback stops or moves on, so no plaintext audio outlives the tap.
 */
@Singleton
class VoicePlaybackController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val focus = AudioFocusGuard(context)
    private val _state = MutableStateFlow(VoicePlayback())
    val state: StateFlow<VoicePlayback> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var currentId: String? = null
    private var currentPath: String? = null
    private var queue: VoiceQueue? = null
    private var speed = VoiceSpeed.NORMAL
    private var ticker: Job? = null

    /** Plays [filePath] as message [messageId], replacing whatever was playing. */
    fun play(messageId: String, filePath: String, upNext: VoiceQueue? = null) {
        stop()
        queue = upNext
        if (!focus.request(::pause)) {
            AppLogger.warn(TAG, "audio focus denied; playing anyway")
        }
        if (open(messageId, filePath)) publish(playing = true) else stop()
    }

    fun pause() {
        val media = player ?: return
        runCatching { media.pause() }
        publish(playing = false)
    }

    fun resume() {
        val media = player ?: return
        runCatching { media.start() }
        media.applySpeed(speed)
        publish(playing = true)
    }

    /** Scrubs to [fraction] of the message, 0..1. */
    fun seekTo(fraction: Float) {
        val media = player ?: return
        val target = (fraction.coerceIn(0f, 1f) * media.duration).toInt()
        runCatching { media.seekTo(target) }
        publish(playing = _state.value.playing)
    }

    /** 1× → 1.5× → 2× → 1×. The choice sticks for the next message too. */
    fun toggleSpeed() {
        speed = speed.next()
        val playing = _state.value.playing
        // A paused message only records the new speed — see [applySpeed].
        if (playing) player?.applySpeed(speed)
        publish(playing = playing)
    }

    /** Ends playback, gives back audio focus and deletes the decrypted copy. */
    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let { media ->
            runCatching { media.stop() }
            media.release()
        }
        player = null
        currentId = null
        queue = null
        focus.abandon()
        deleteTemp(currentPath)
        currentPath = null
        _state.value = VoicePlayback(speed = speed)
    }

    /** Final teardown: after this the controller's ticker scope is gone for good. */
    fun release() {
        stop()
        scope.cancel()
    }

    private fun open(messageId: String, filePath: String): Boolean {
        val media = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(voiceAttributes())
                setDataSource(filePath)
                prepare()
            }
        }.getOrElse { error ->
            AppLogger.warn(TAG, "cannot play voice message: ${error.javaClass.simpleName}")
            null
        } ?: return false

        media.setOnCompletionListener { advance() }
        media.setOnErrorListener { _, what, _ ->
            AppLogger.warn(TAG, "player error $what")
            stop()
            true
        }
        player = media
        currentId = messageId
        currentPath = filePath
        media.start()
        media.applySpeed(speed)
        return true
    }

    /**
     * Auto-advance. The caller owns both the order and the meaning of "unplayed"; all we do is
     * ask it to decrypt the next candidate when the current one runs out.
     */
    private fun advance() {
        val pending = queue
        val next = pending?.let { nextTrackId(it.upNext, currentId) }
        if (pending == null || next == null) {
            stop()
            return
        }
        scope.launch {
            val path = pending.openTrack(next)
            if (path == null) stop() else play(next, path, pending)
        }
    }

    /** Emits the position once, and keeps the ticker alive only while the player is running. */
    private fun publish(playing: Boolean) {
        ticker?.cancel()
        _state.value = snapshot(currentId, player, playing, speed)
        ticker = if (!playing) {
            null
        } else {
            scope.launch {
                while (isActive) {
                    delay(TICK_INTERVAL_MS)
                    _state.value = snapshot(currentId, player, true, speed)
                }
            }
        }
    }
}

/**
 * Audio focus for as long as one voice message lasts. Transient on purpose: the music the user
 * paused to listen to a message comes back on its own afterwards.
 */
private class AudioFocusGuard(context: Context) {

    private val manager = context.getSystemService(AudioManager::class.java)
    private var granted: AudioFocusRequest? = null

    fun request(onLoss: () -> Unit): Boolean {
        val audio = manager ?: return false
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(voiceAttributes())
            .setOnAudioFocusChangeListener { change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) onLoss()
            }
            .build()
        granted = focus
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        val focus = granted ?: return
        granted = null
        manager?.abandonAudioFocusRequest(focus)
    }
}

/**
 * Retunes a *running* player. Writing `playbackParams` starts a paused one, so the speed chip
 * deliberately leaves a paused message alone and applies the change on the next resume.
 */
private fun MediaPlayer.applySpeed(speed: VoiceSpeed) {
    runCatching { playbackParams = playbackParams.setSpeed(speed.factor) }
}

/** Speech on the media stream, so the volume rocker behaves the way the user expects. */
private fun voiceAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/** Reads the player's clock into a UI state; a player that has gone away reads as stopped. */
private fun snapshot(messageId: String?, media: MediaPlayer?, playing: Boolean, speed: VoiceSpeed): VoicePlayback {
    val position = media?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0
    val duration = media?.let { runCatching { it.duration }.getOrDefault(0) } ?: 0
    return VoicePlayback(
        messageId = messageId,
        playing = playing,
        progress = progressOf(position, duration),
        elapsedMs = position.toLong(),
        speed = speed,
    )
}

/** The temp file is the player's to clean up: nothing decrypted survives the message. */
private fun deleteTemp(path: String?) {
    if (path == null) return
    runCatching { File(path).delete() }
}

/** The entry after [currentId], or the head of [upNext] when the finished message is not in it. */
internal fun nextTrackId(upNext: List<String>, currentId: String?): String? =
    upNext.getOrNull(upNext.indexOf(currentId) + 1)

/** Position as a 0..1 fraction; a duration the codec has not worked out yet reads as the start. */
internal fun progressOf(positionMs: Int, durationMs: Int): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
