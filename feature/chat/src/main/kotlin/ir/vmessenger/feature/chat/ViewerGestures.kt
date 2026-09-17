package ir.vmessenger.feature.chat

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOMED_EPSILON = 1.01f

/** How far a photo at rest has to be dragged, up or down, to close the viewer when let go. */
internal val ViewerDismissDistance = 96.dp

/** A flick this fast closes it too, however short. */
internal val ViewerDismissVelocity = 1200.dp

/**
 * What the viewer's fingers have done to the photo so far.
 *
 * Closing is decided once, on release. It used to be decided on every move event: past the
 * threshold each frame of the same swipe asked to close again, and each ask popped another screen
 * off the stack — the viewer, then the conversation, then the tabs — leaving a black window and no
 * way back short of restarting the app. The drag now also works upwards, and a drag that stops
 * short springs back instead of leaving the photo wherever the finger lifted.
 */
@Stable
internal class ViewerGestureState(
    private val dismissPx: Float,
    private val flingPx: Float,
) {
    var scale by mutableFloatStateOf(MIN_SCALE)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var dragY by mutableFloatStateOf(0f)
        private set

    private var dismissed = false
    private var settling: Job? = null

    private val zoomed: Boolean get() = scale > ZOOMED_EPSILON

    fun toggleZoom() {
        scale = if (zoomed) MIN_SCALE else DOUBLE_TAP_SCALE
        offset = Offset.Zero
        dragY = 0f
    }

    fun transform(pan: Offset, zoom: Float) {
        settling?.cancel()
        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        if (zoomed) {
            offset += pan
            dragY = 0f
        } else {
            offset = Offset.Zero
            dragY += pan.y
        }
    }

    fun release(velocityY: Float, scope: CoroutineScope, onDismiss: () -> Unit) {
        val flung = abs(velocityY) >= flingPx && velocityY * dragY > 0
        when {
            zoomed || dismissed -> Unit
            abs(dragY) >= dismissPx || flung -> {
                dismissed = true
                onDismiss()
            }
            else -> settling = scope.launch { animate(dragY, 0f) { value, _ -> dragY = value } }
        }
    }
}

/**
 * `detectTransformGestures`, plus the one thing it does not report: the moment the fingers lift,
 * with the vertical velocity they left at.
 */
internal suspend fun PointerInputScope.detectZoomPanOrSwipe(
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onRelease: (velocityY: Float) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    val velocity = VelocityTracker()
    var zoomSoFar = 1f
    var panSoFar = Offset.Zero
    var pastSlop = false
    var canceled = false
    do {
        val event = awaitPointerEvent()
        canceled = event.changes.any { it.isConsumed }
        if (!canceled) {
            event.changes.firstOrNull()?.let { velocity.addPosition(it.uptimeMillis, it.position) }
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (!pastSlop) {
                zoomSoFar *= zoom
                panSoFar += pan
                val zoomMotion = abs(1 - zoomSoFar) * event.calculateCentroidSize(useCurrent = false)
                val slop = viewConfiguration.touchSlop
                pastSlop = zoomMotion > slop || panSoFar.getDistance() > slop
            }
            if (pastSlop) {
                if (zoom != 1f || pan != Offset.Zero) onTransform(pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    } while (!canceled && event.changes.any { it.pressed })
    if (pastSlop) onRelease(velocity.calculateVelocity().y)
}
