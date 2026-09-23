package ir.vmessenger.feature.chat.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.feature.chat.R

private val BubbleMinWidth = 168.dp
private val UnplayedDotSize = 7.dp
private val ChipPadding = 6.dp
private const val CHIP_CONTAINER_ALPHA = 0.12f

/**
 * How the shared player sees this one message. The screen fills it in only for the message the
 * player is loaded with; every other bubble gets the default, which reads as "not playing".
 * [unplayed] is an incoming-only idea — an outgoing bubble always passes false.
 */
@Immutable
internal data class VoiceBubblePlayback(
    val playing: Boolean = false,
    val progress: Float = 0f,
    val elapsedMs: Long = 0L,
    val speed: VoiceSpeed = VoiceSpeed.NORMAL,
    val unplayed: Boolean = false,
)

/**
 * A voice message inside a bubble: play/pause, the waveform, the duration, and — only while
 * this is the loaded message — the speed chip. State and behaviour both belong to the caller;
 * this draws what it is given and reports taps.
 *
 * Laid out left to right in both directions, as one media control: the waveform is a timeline
 * that never mirrors (see [WaveformBar]), and in a right-to-left layout the play button used to
 * move to the right on its own — to the timeline's end, away from where playback starts.
 */
@Suppress("LongParameterList") // Compose slot API: the message, its playback state, three actions.
@Composable
internal fun VoiceBubbleContent(
    waveform: ByteArray?,
    durationMs: Long,
    playback: VoiceBubblePlayback,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleSpeed: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = playback.playing || playback.progress > 0f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        VoicePlayerRow(
            waveform = waveform,
            durationMs = durationMs,
            playback = playback,
            loaded = loaded,
            actions = VoicePlayerActions(onPlayPause, onSeek, onToggleSpeed, onLongPress),
            modifier = modifier,
        )
    }
}

/** What the player row can do, bundled so the row stays within a readable parameter list. */
@Stable
private class VoicePlayerActions(
    val onPlayPause: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onToggleSpeed: () -> Unit,
    val onLongPress: () -> Unit,
)

@Composable
@Suppress("LongParameterList") // The message, its playback state, and the actions bundle.
private fun VoicePlayerRow(
    waveform: ByteArray?,
    durationMs: Long,
    playback: VoiceBubblePlayback,
    loaded: Boolean,
    actions: VoicePlayerActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.widthIn(min = BubbleMinWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        VmIconButton(
            icon = if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (playback.playing) R.string.feature_chat_voice_pause else R.string.feature_chat_voice_play,
            ),
            onClick = actions.onPlayPause,
        )
        Column(modifier = Modifier.weight(1f)) {
            WaveformBar(
                waveform = waveform,
                progress = playback.progress,
                onSeek = actions.onSeek,
                onLongPress = actions.onLongPress,
            )
            MetaRow(
                // Mid-message the elapsed time is the useful number; at rest, the length is.
                label = VmTextFormat.duration(if (loaded) playback.elapsedMs else durationMs),
                playback = playback,
                showSpeed = loaded,
                onToggleSpeed = actions.onToggleSpeed,
            )
        }
    }
}

@Composable
private fun MetaRow(
    label: String,
    playback: VoiceBubblePlayback,
    showSpeed: Boolean,
    onToggleSpeed: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = VmSpacing.xxs),
    ) {
        VmText(
            text = label,
            style = VmTheme.typography.bodyXsMedium,
            color = VmTheme.colors.textSecondary,
        )
        if (playback.unplayed) UnplayedDot()
        Spacer(modifier = Modifier.weight(1f))
        SpeedChip(speed = playback.speed, visible = showSpeed, onClick = onToggleSpeed)
    }
}

/** The "you have not listened to this yet" dot, the audio counterpart of an unread row. */
@Composable
private fun UnplayedDot() {
    val description = stringResource(R.string.feature_chat_voice_unplayed)
    Box(
        modifier = Modifier
            .semantics { contentDescription = description }
            .size(UnplayedDotSize)
            .background(color = VmTheme.colors.textAccent, shape = CircleShape),
    )
}

/**
 * Tinted from the bubble's own content colour, so one chip works on either side of the chat.
 *
 * Laid out even while hidden: it is taller than the duration beside it, and a chip that came and
 * went with playback grew the bubble on Play and shrank it at the end, nudging the whole list.
 */
@Composable
private fun SpeedChip(speed: VoiceSpeed, visible: Boolean, onClick: () -> Unit) {
    val label = stringResource(speedLabelRes(speed))
    val description = stringResource(R.string.feature_chat_voice_speed, label)
    VmSurface(
        onClick = onClick,
        enabled = visible,
        shape = CircleShape,
        color = LocalVmContentColor.current.copy(alpha = CHIP_CONTAINER_ALPHA),
        contentColor = LocalVmContentColor.current,
        modifier = if (visible) {
            Modifier.semantics { contentDescription = description }
        } else {
            Modifier
                .alpha(0f)
                .clearAndSetSemantics { }
        },
    ) {
        VmText(
            text = label,
            style = VmTheme.typography.bodyXsMedium,
            modifier = Modifier.padding(horizontal = ChipPadding, vertical = VmSpacing.xxs),
        )
    }
}

/** `۱×` / `۱٫۵×` / `۲×` — Persian digits and separator live in the resources, not here. */
private fun speedLabelRes(speed: VoiceSpeed): Int = when (speed) {
    VoiceSpeed.NORMAL -> R.string.feature_chat_voice_speed_normal
    VoiceSpeed.FAST -> R.string.feature_chat_voice_speed_fast
    VoiceSpeed.FASTEST -> R.string.feature_chat_voice_speed_fastest
}
