package ir.vmessenger.feature.chat.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.feature.chat.R
import kotlin.math.max
import kotlin.math.roundToInt

private val BarsHeight = 28.dp
private const val BAR_GAP_FRACTION = 0.35f

/** Even silence gets a visible stub, so the bar reads as a bar and not as a gap. */
private const val MIN_BAR_FRACTION = 0.14f
private const val UNPLAYED_ALPHA = 0.35f
private const val MAX_BAR_LEVEL = 255f

/** Below this many samples a waveform says nothing worth drawing; a flat line is honest. */
private const val MIN_USABLE_BARS = 8

/**
 * The [WAVEFORM_BUCKETS]-bar waveform, doubling as the scrubber: bars before [progress] are
 * drawn in the content colour, the rest faded. A message that arrived without a waveform (an
 * old row, or a sender that sent none) draws flat bars rather than nothing, so the bubble keeps
 * its shape and stays seekable.
 *
 * Bars run left to right in both layout directions: this is a timeline, not text.
 *
 * [onLongPress] is forwarded because this bar covers most of the bubble: its own tap detector
 * consumes the gesture, so without it a long press on a voice message would reach nothing and
 * the message-actions sheet would be unopenable there.
 */
@Composable
internal fun WaveformBar(
    waveform: ByteArray?,
    progress: Float,
    onSeek: (Float) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playedColor = LocalVmContentColor.current
    val pendingColor = playedColor.copy(alpha = UNPLAYED_ALPHA)
    val description = stringResource(R.string.feature_chat_voice_waveform)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(BarsHeight)
            .semantics { contentDescription = description }
            .pointerInput(onSeek, onLongPress) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { offset -> onSeek(seekFraction(offset.x, size.width.toFloat())) },
                )
            }
            .pointerInput(onSeek) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onSeek(seekFraction(offset.x, size.width.toFloat())) },
                ) { change, _ ->
                    onSeek(seekFraction(change.position.x, size.width.toFloat()))
                }
            },
    ) {
        val slot = size.width / WAVEFORM_BUCKETS
        val barWidth = max(1f, slot * (1f - BAR_GAP_FRACTION))
        val playedBars = (progress.coerceIn(0f, 1f) * WAVEFORM_BUCKETS).roundToInt()
        for (index in 0 until WAVEFORM_BUCKETS) {
            val barHeight = max(size.height * MIN_BAR_FRACTION, size.height * waveformLevel(waveform, index))
            drawRect(
                color = if (index < playedBars) playedColor else pendingColor,
                topLeft = Offset(index * slot, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

/** Bar [index] as a 0..1 height, from a waveform of any length — including none at all. */
internal fun waveformLevel(waveform: ByteArray?, index: Int): Float {
    if (waveform == null || waveform.size < MIN_USABLE_BARS) return 0f
    val bucket = index * waveform.size / WAVEFORM_BUCKETS
    return (waveform[bucket].toInt() and 0xFF) / MAX_BAR_LEVEL
}

/** Where a touch at [x] falls in a bar [width] wide; a bar of no width reads as the start. */
internal fun seekFraction(x: Float, width: Float): Float =
    if (width > 0f) (x / width).coerceIn(0f, 1f) else 0f
