package ir.vmessenger.feature.chat.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.format.VmTextFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.vm
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
 * The hint follows the finger through [slide] (0..1 of the way to the cancel threshold); the
 * `dp` offset is mirrored for RTL on its own, which is why the sign here is always negative.
 */
@Composable
internal fun RowScope.RecordingRow(
    state: VoiceRecorderState,
    locked: Boolean,
    slide: Float,
    onCancel: () -> Unit,
) {
    IconButton(onClick = onCancel, modifier = Modifier.size(VmSizes.touchTarget)) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.feature_chat_voice_cancel),
            tint = MaterialTheme.colorScheme.error,
        )
    }
    AmplitudeDot(amplitude = state.amplitude)
    Text(
        text = VmTextFormat.duration(state.elapsedMs),
        style = MaterialTheme.typography.labelLarge,
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
            .background(color = MaterialTheme.vm.recordingRed, shape = CircleShape),
    )
}

@Composable
private fun Hint(locked: Boolean, slide: Float, modifier: Modifier = Modifier) {
    val progress = slide.coerceIn(0f, 1f)
    Text(
        text = stringResource(
            if (locked) R.string.feature_chat_voice_locked else R.string.feature_chat_voice_slide_to_cancel,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .offset(x = -(SlideTravel * progress))
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
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
