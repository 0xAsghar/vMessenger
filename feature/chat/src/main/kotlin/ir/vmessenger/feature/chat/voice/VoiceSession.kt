package ir.vmessenger.feature.chat.voice

import ir.vmessenger.core.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The voice half of one conversation: the recorder, the shared player and the send.
 *
 * It lives beside the conversation's ViewModel rather than inside it so the chat screen's
 * state machine does not grow a second one. The recorder is per screen (only one
 * recording can be in flight anyway) while the player is app-wide, which is what stops
 * two voice messages playing over each other.
 */
class VoiceSession(
    private val recorder: VoiceRecorder,
    private val playback: VoicePlaybackController,
    private val scope: CoroutineScope,
    private val ports: VoicePorts,
) {
    /**
     * What the session needs from the conversation, as plain functions so it never
     * reaches for a repository.
     */
    class VoicePorts(
        /** Queues the recording for delivery; the file is consumed (encrypted and deleted). */
        val send: suspend (VoiceRecording) -> Unit,
        /** Decrypts a stored voice message into a temp file the player may delete. */
        val open: suspend (String) -> String?,
        /** Voice messages after [messageId] that have not been played, in order. */
        val upNext: (String) -> List<String>,
        /** Records the first listen, which clears the message's unplayed dot for good. */
        val markPlayed: suspend (String) -> Unit,
    )

    val recorderState: StateFlow<VoiceRecorderState> get() = recorder.state
    val playbackState: StateFlow<VoicePlayback> get() = playback.state

    /**
     * Starts recording. Returns false when the microphone is unavailable, which the mic
     * gesture reads as "this press did not become a recording".
     */
    fun startRecording(): Boolean = recorder.start(onMaxDuration = ::queue)

    fun cancelRecording() = recorder.cancel()

    /** Stops and queues, unless the press was too short to be a message. */
    fun sendRecording() {
        recorder.stop()?.let(::queue)
    }

    /**
     * Play, or pause what is already playing. A different message always starts from its
     * own beginning: resuming someone else's position would be meaningless.
     */
    fun toggle(messageId: String) {
        val current = playback.state.value
        when {
            current.messageId != messageId -> open(messageId)
            current.playing -> playback.pause()
            else -> playback.resume()
        }
    }

    fun seek(fraction: Float) = playback.seekTo(fraction)

    fun toggleSpeed() = playback.toggleSpeed()

    /** Stops playback when the conversation leaves the screen; a cancelled recording goes with it. */
    fun detach() {
        recorder.cancel()
        playback.stop()
    }

    private fun open(messageId: String) {
        scope.launch {
            val path = ports.open(messageId)
            if (path == null) {
                AppLogger.warn(TAG, "voice message $messageId could not be opened")
                return@launch
            }
            // Marked on start, not on completion: the dot means "you have heard this", and a
            // listener who stops halfway has.
            ports.markPlayed(messageId)
            playback.play(messageId, path, VoiceQueue(ports.upNext(messageId), ports.open))
        }
    }

    private fun queue(recording: VoiceRecording) {
        scope.launch { ports.send(recording) }
    }

    private companion object {
        const val TAG = "VoicePlayback"
    }
}
