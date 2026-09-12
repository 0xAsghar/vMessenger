package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.vm
import kotlin.math.roundToInt

private val DotMinSize = 8.dp
private val DotMaxGrowth = 6.dp
private const val MIN_HINT_ALPHA = 0.25f
private const val SLIDE_RANGE_PX = 180f

/**
 * Replaces the composer while a voice message is being recorded: blinking dot sized by the live
 * [amplitude], elapsed time, a slide-to-cancel hint that follows [slideOffset], and — once
 * [locked] — an explicit send button.
 */
@Suppress("LongParameterList") // Compose slot API: live recorder state plus two actions.
@Composable
fun RecordingBar(
    elapsedMs: Long,
    amplitude: Float,
    locked: Boolean,
    slideOffset: Float,
    onCancel: () -> Unit,
    onSendLocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = VmElevation.bar,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
            modifier = Modifier.padding(horizontal = VmSpacing.md, vertical = VmSpacing.sm),
        ) {
            IconButton(onClick = onCancel, modifier = Modifier.size(VmSizes.touchTarget)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.vm_recording_cancel),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            AmplitudeDot(amplitude)
            Text(
                text = VmDateFormat.duration(elapsedMs),
                style = MaterialTheme.typography.labelLarge,
            )
            CancelHint(slideOffset = slideOffset, modifier = Modifier.weight(1f))
            if (locked) {
                IconButton(onClick = onSendLocked, modifier = Modifier.size(VmSizes.touchTarget)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.vm_recording_send),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AmplitudeDot(amplitude: Float) {
    val size = DotMinSize + DotMaxGrowth * amplitude.coerceIn(0f, 1f)
    val description = stringResource(R.string.vm_recording_indicator)
    Box(
        modifier = Modifier
            .semantics { contentDescription = description }
            .size(size)
            .background(color = MaterialTheme.vm.recordingRed, shape = CircleShape),
    )
}

@Composable
private fun CancelHint(slideOffset: Float, modifier: Modifier = Modifier) {
    val progress = slideOffset.coerceIn(0f, 1f)
    Text(
        text = stringResource(R.string.vm_recording_slide_to_cancel),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .offset { IntOffset(x = (-progress * SLIDE_RANGE_PX).roundToInt(), y = 0) }
            .alpha(maxOf(MIN_HINT_ALPHA, 1f - progress)),
    )
}
