package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Where a [VmSheetScaffold]'s sheet rests — peeking or fully up — and the calls that move it.
 * Survives a configuration change, so a rotated map keeps its sheet where the user left it.
 */
@Stable
class VmSheetScaffoldState internal constructor(initiallyExpanded: Boolean) {
    /** The rest the sheet is at, or heading for. */
    var expanded by mutableStateOf(initiallyExpanded)
        private set

    /** Pixels below fully expanded: 0 is up, [collapsedOffset] is peeking. NaN until measured. */
    internal var offset by mutableFloatStateOf(Float.NaN)
        private set

    internal var collapsedOffset by mutableFloatStateOf(0f)
        private set

    private val mutex = MutatorMutex()
    private var moving = false

    suspend fun expand() = animateTo(expand = true)

    suspend fun collapse() = animateTo(expand = false)

    /** The sheet measured itself; a resting sheet re-seats on the new anchors, a moving one is left be. */
    internal fun onMeasured(sheetHeight: Int, peekPx: Int) {
        collapsedOffset = (sheetHeight - peekPx).coerceAtLeast(0).toFloat()
        if (!moving) offset = if (expanded) 0f else collapsedOffset
    }

    internal fun dragBy(delta: Float): Float {
        if (offset.isNaN()) return 0f
        val before = offset
        offset = (offset + delta).coerceIn(0f, collapsedOffset)
        return offset - before
    }

    internal suspend fun stop() = mutex.mutate(MutatePriority.UserInput) { moving = true }

    /** Whether the sheet sits between its two rests, which is when a fling is the sheet's to settle. */
    internal val isBetween: Boolean get() = !offset.isNaN() && offset > 0f && offset < collapsedOffset

    /** Released: a fling decides, otherwise the nearer rest. */
    internal suspend fun settle(velocity: Float, density: Density) {
        val threshold = with(density) { FLING_VELOCITY.toPx() }
        val expand = when {
            velocity < -threshold -> true
            velocity > threshold -> false
            else -> offset < collapsedOffset / 2
        }
        animateTo(expand, velocity)
    }

    private suspend fun animateTo(expand: Boolean, velocity: Float = 0f) {
        expanded = expand
        if (offset.isNaN()) return
        val target = if (expand) 0f else collapsedOffset
        mutex.mutate {
            moving = true
            try {
                animate(offset, target, velocity, SettleSpring) { value, _ -> offset = value }
            } finally {
                moving = false
            }
        }
    }

    internal fun release() {
        moving = false
    }

    companion object {
        internal val Saver = Saver<VmSheetScaffoldState, Boolean>(
            save = { it.expanded },
            restore = { VmSheetScaffoldState(initiallyExpanded = it) },
        )
    }
}

private val SettleSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
private val FLING_VELOCITY = 400.dp
