package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.vm
import kotlin.math.max

private val WaveformHeight = 28.dp
private val WaveformMinWidth = 160.dp
private const val BAR_COUNT = 64
private const val BAR_GAP_FRACTION = 0.35f
private const val MIN_BAR_FRACTION = 0.12f
private const val MAX_AMPLITUDE = 255f
private const val PLAYED_ALPHA = 0.35f

/**
 * Voice message: play/pause, a 64-bar waveform that doubles as a scrubber, and the elapsed or
 * total duration. All state is owned by the caller — this component only reports gestures.
 */
@Suppress("LongParameterList") // Compose slot API: playback state and two independent callbacks.
@Composable
fun VoiceBubbleContent(
    waveform: ByteArray,
    durationMs: Long,
    playback: VoicePlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (playback.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        modifier = modifier.widthIn(min = WaveformMinWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.sm),
    ) {
        IconButton(onClick = onPlayPause, modifier = Modifier.size(VmSizes.touchTarget)) {
            Icon(
                imageVector = if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playback.playing) R.string.vm_voice_pause else R.string.vm_voice_play,
                ),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            WaveformBar(waveform = waveform, progress = progress, onSeek = onSeek)
            Text(
                text = VmDateFormat.duration(if (playback.playing) playback.positionMs else durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaveformBar(
    waveform: ByteArray,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playedColor = MaterialTheme.vm.tickRead
    val pendingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PLAYED_ALPHA)
    val description = stringResource(R.string.vm_voice_waveform)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(WaveformHeight)
            .semantics { contentDescription = description }
            .pointerInput(waveform) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(waveform) {
                detectHorizontalDragGestures { change, _ ->
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val slot = size.width / BAR_COUNT
        val barWidth = max(1f, slot * (1f - BAR_GAP_FRACTION))
        for (index in 0 until BAR_COUNT) {
            val amplitude = waveform.amplitudeAt(index)
            val barHeight = max(size.height * MIN_BAR_FRACTION, size.height * amplitude)
            val color = if (index.toFloat() / BAR_COUNT <= progress) playedColor else pendingColor
            drawBar(color, index * slot, barWidth, barHeight)
        }
    }
}

private fun DrawScope.drawBar(
    color: Color,
    x: Float,
    width: Float,
    height: Float,
) {
    drawRect(
        color = color,
        topLeft = Offset(x, (size.height - height) / 2f),
        size = Size(width, height),
    )
}

/** Amplitude 0..1 for bucket [index], tolerating waveforms of any length (including empty). */
private fun ByteArray.amplitudeAt(index: Int): Float {
    if (isEmpty()) return MIN_BAR_FRACTION
    val bucket = index * size / BAR_COUNT
    return (this[bucket].toInt() and 0xFF) / MAX_AMPLITUDE
}
