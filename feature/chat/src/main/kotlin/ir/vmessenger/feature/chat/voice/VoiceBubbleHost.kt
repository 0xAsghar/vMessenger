package ir.vmessenger.feature.chat.voice

import androidx.compose.runtime.Stable

/**
 * What a voice bubble needs from the screen: the shared player's state, and the three
 * things a bubble can ask of it.
 *
 * Bubbles are drawn inside a `LazyColumn`, so they get this rather than a ViewModel —
 * one stable object instead of three lambdas re-created per row.
 */
@Stable
internal class VoiceBubbleHost(
    val playback: VoicePlayback,
    val onToggle: (String) -> Unit,
    val onSeek: (Float) -> Unit,
    val onToggleSpeed: () -> Unit,
) {
    /** This message's slice of the shared player; every other bubble reads as "not playing". */
    fun stateFor(messageId: String, unplayed: Boolean): VoiceBubblePlayback =
        if (playback.messageId != messageId) {
            VoiceBubblePlayback(unplayed = unplayed)
        } else {
            VoiceBubblePlayback(
                playing = playback.playing,
                progress = playback.progress,
                elapsedMs = playback.elapsedMs,
                speed = playback.speed,
                unplayed = false,
            )
        }
}
