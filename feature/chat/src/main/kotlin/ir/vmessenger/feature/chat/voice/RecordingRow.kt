package ir.vmessenger.feature.chat.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.component.VmIcon
import ir.vmessenger.core.designsystem.component.VmIconButton
import ir.vmessenger.core.designsystem.component.VmText
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.feature.chat.R

private val DotMinSize = 8.dp
private val DotMaxGrowth = 6.dp

/** How far the hint travels while the finger slides; it is a cue, not a measurement. */
private val SlideTravel = 56.dp
private const val MIN_HINT_ALPHA = 0.2f

/**
 * What the composer shows while a voice message is being recorded: the live level, the elapsed
 * time in Persian digits, and the two ways out — slide the hint away to cancel, or lift a
 * finger to send.
 *
 * This is a *row*, not a bar, and it deliberately excludes the mic: it is handed to
 * [ir.vmessenger.core.designsystem.component.Composer] as its recording content, which keeps
 * the same mic button in the same position. The finger is still down on that button, and one
 * that left composition would never see the release — the recording could then never end.
 * Once the gesture locks, that button turns itself into send, which is why there is no send
 * button here.
 *
 * The mic ends the composer row, so the children run toward it: the delete button farthest from
 * the finger, the lock hint right next to the button it points at.
 *
 * The hint follows the finger through [slide] (0..1 of the way to the cancel threshold). Cancel
 * is toward the layout start — away from the mic — so the offset is negative; a `dp` offset is
 * mirrored for RTL on its own.
 */
@Composable
internal fun RowScope.RecordingRow(
    state: VoiceRecorderState,
    locked: Boolean,
    slide: Float,
    onCancel: () -> Unit,
) {
    VmIconButton(
        icon = Icons.Outlined.Delete,
        contentDescription = stringResource(R.string.feature_chat_voice_cancel),
        onClick = onCancel,
        tint = VmTheme.colors.iconCritical,
    )
    AmplitudeDot(amplitude = state.amplitude)
    VmText(
        text = VmTextFormat.duration(state.elapsedMs),
        style = VmTheme.typography.bodyMdMedium,
        modifier = Modifier.padding(horizontal = VmSpacing.sm),
    )
    Hint(locked = locked, slide = slide, modifier = Modifier.weight(1f))
    if (!locked) LockHint()
}

/** The recording indicator, grown by the live level so a silent mic is visibly silent. */
@Composable
private fun AmplitudeDot(amplitude: Float) {
    val description = stringResource(R.string.feature_chat_voice_recording)
    Box(
        modifier = Modifier
            .semantics { contentDescription = description }
            .size(DotMinSize + DotMaxGrowth * amplitude.coerceIn(0f, 1f))
            .background(color = VmTheme.colors.recordingRed, shape = CircleShape),
    )
}

@Composable
private fun Hint(locked: Boolean, slide: Float, modifier: Modifier = Modifier) {
    val progress = slide.coerceIn(0f, 1f)
    VmText(
        text = stringResource(
            if (locked) R.string.feature_chat_voice_locked else R.string.feature_chat_voice_slide_to_cancel,
        ),
        style = VmTheme.typography.bodySmMedium,
        color = VmTheme.colors.textSecondary,
        modifier = modifier
            .offset(x = -SlideTravel * progress)
            .alpha(if (locked) 1f else maxOf(MIN_HINT_ALPHA, 1f - progress)),
    )
}

/** Drag up past the mic to go hands-free; gone once locked, because it has been taken. */
@Composable
private fun LockHint() {
    val description = stringResource(R.string.feature_chat_voice_lock_hint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        VmIcon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = VmTheme.colors.iconSecondary,
        )
        VmIcon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = VmTheme.colors.iconSecondary,
        )
    }
}
