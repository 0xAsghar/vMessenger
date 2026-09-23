package ir.vmessenger.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.LocalAppObscured
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.foundation.VmModalWindow
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A modal sheet: rises from the bottom edge over a scrim and leaves when the scrim is tapped, the
 * sheet is dragged down, or back is pressed — in each case sliding out first and only then calling
 * [onDismissRequest]. A caller that closes it itself (an action was picked) just stops composing it.
 *
 * The sheet is as tall as its content and never taller than the space under the status bar; a list
 * inside it scrolls, and pulling that list down past its top pulls the sheet down with it. Its
 * bottom runs under the navigation bar and lifts above the keyboard, so the last row is never
 * behind either.
 *
 * **Nothing above the lock**: the sheet is a window of its own, which the lock overlay inside the
 * app's window cannot cover. While the app is obscured the sheet is not composed at all — it
 * comes back as it was when the user unlocks, rather than being dismissed behind their back.
 */
@Composable
fun VmModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalAppObscured.current) return
    val motion = remember { SheetMotion() }
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val hide: () -> Unit = remember(motion, scope) {
        {
            scope.launch {
                motion.hide()
                dismiss()
            }
        }
    }
    VmModalWindow(onBackPress = hide) {
        SheetFrame(motion = motion, hide = hide, modifier = modifier, content = content)
    }
}

@Composable
private fun SheetFrame(
    motion: SheetMotion,
    hide: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = VmTheme.colors
    val density = LocalDensity.current
    LaunchedEffect(motion) { motion.enter() }
    val nestedScroll = remember(motion, density) { SheetNestedScroll(motion, density, hide) }
    Box(modifier = Modifier.fillMaxSize()) {
        Scrim(motion = motion, hide = hide)
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = TOP_GAP),
        ) {
            VmSurface(
                shape = VmShapes.sheet,
                color = c.bgElevated,
                contentColor = c.textPrimary,
                modifier = modifier
                    .widthIn(max = MAX_WIDTH)
                    .fillMaxWidth()
                    .onSizeChanged { motion.height = it.height }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            val offset = motion.offset.coerceAtMost(placeable.height.toFloat())
                            placeable.place(0, offset.roundToInt())
                        }
                    }
                    .nestedScroll(nestedScroll)
                    .draggable(
                        state = rememberDraggableState { delta -> motion.dragBy(delta) },
                        orientation = Orientation.Vertical,
                        onDragStarted = { motion.stop() },
                        onDragStopped = { velocity ->
                            if (motion.shouldHide(velocity, density)) hide() else motion.settle(velocity)
                        },
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(bottom = BOTTOM_GAP),
                ) {
                    DragHandle()
                    content()
                }
            }
        }
    }
}

/** Dims the screen as far as the sheet is up, and closes it when tapped. */
@Composable
private fun Scrim(motion: SheetMotion, hide: () -> Unit) {
    val scrim = VmTheme.colors.scrim
    val closeLabel = stringResource(R.string.vm_close)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(color = scrim, alpha = motion.visibleFraction) }
            .pointerInput(hide) { detectTapGestures { hide() } }
            // Read last: the sheet's own content comes first to a screen reader.
            .semantics {
                traversalIndex = 1f
                contentDescription = closeLabel
                onClick {
                    hide()
                    true
                }
            },
    )
}

/** The short bar at the top that says "this can be pulled". Decoration only; back closes the sheet. */
@Composable
private fun ColumnScope.DragHandle() {
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = HANDLE_TOP, bottom = HANDLE_BOTTOM)
            .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
            .background(VmTheme.colors.iconTertiary, VmShapes.pill),
    )
}

/**
 * Where the sheet is: [offset] pixels below its resting place, from 0 (fully up) to [height] (fully
 * down, off screen). Infinite until the sheet has been measured, which is also "not shown yet".
 */
@Stable
private class SheetMotion {
    var height by mutableIntStateOf(0)

    var offset by mutableFloatStateOf(Float.POSITIVE_INFINITY)
        private set

    private val mutex = MutatorMutex()

    /** How much of the sheet is on screen; the scrim follows it, so it fades as the sheet leaves. */
    val visibleFraction: Float
        get() = if (height == 0 || offset.isInfinite()) 0f else (1f - offset / height).coerceIn(0f, 1f)

    /** Moves the sheet by a finger's [delta], no higher than its resting place; returns what it used. */
    fun dragBy(delta: Float): Float {
        if (height == 0 || offset.isInfinite()) return 0f
        val before = offset
        offset = (offset + delta).coerceIn(0f, height.toFloat())
        return offset - before
    }

    /** Waits for the first measurement, then slides up from just below the edge. */
    suspend fun enter() {
        val measured = snapshotFlow { height }.first { it > 0 }
        if (offset.isInfinite()) offset = measured.toFloat()
        animateTo(0f)
    }

    suspend fun hide() {
        if (height == 0 || offset.isInfinite()) return
        animateTo(height.toFloat())
    }

    /** Back to resting after a drag that did not go far or fast enough to close. */
    suspend fun settle(velocity: Float) = animateTo(0f, velocity)

    /** A finger on the sheet takes over from whatever animation is running. */
    suspend fun stop() = mutex.mutate(MutatePriority.UserInput) {}

    /** A drag ended: close if it was flung down, or let go more than [HIDE_FRACTION] of the way. */
    fun shouldHide(velocity: Float, density: Density): Boolean {
        val threshold = with(density) { FLING_VELOCITY.toPx() }
        return when {
            velocity > threshold -> true
            velocity < -threshold -> false
            else -> offset > height * HIDE_FRACTION
        }
    }

    /** Whether the sheet has been pulled away from its resting place at all. */
    val isDisplaced: Boolean get() = offset > 0f && !offset.isInfinite()

    private suspend fun animateTo(target: Float, velocity: Float = 0f) = mutex.mutate {
        animate(
            initialValue = offset,
            targetValue = target,
            initialVelocity = velocity,
            animationSpec = SheetSpring,
        ) { value, _ -> offset = value }
    }
}

/**
 * Lets a list inside the sheet and the sheet itself share one gesture: pulling the list down past
 * its top moves the sheet, and pushing up raises the sheet back to rest before the list scrolls.
 * A fling that ends while the sheet is displaced decides whether it closes; one that ends with the
 * sheet at rest was the list's, and leaves the sheet alone.
 */
private class SheetNestedScroll(
    private val motion: SheetMotion,
    private val density: Density,
    private val hide: () -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && source == NestedScrollSource.UserInput) {
            Offset(0f, motion.dragBy(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, motion.dragBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!motion.isDisplaced) return Velocity.Zero
        if (motion.shouldHide(available.y, density)) hide() else motion.settle(available.y)
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!motion.isDisplaced) return Velocity.Zero
        if (motion.shouldHide(available.y, density)) hide() else motion.settle(available.y)
        return available
    }
}

private val SheetSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
private const val HIDE_FRACTION = 0.4f
private val FLING_VELOCITY = 400.dp
private val MAX_WIDTH = 640.dp
private val TOP_GAP = 48.dp
private val BOTTOM_GAP = 8.dp
private val HANDLE_TOP = 10.dp
private val HANDLE_BOTTOM = 6.dp
private val HANDLE_WIDTH = 32.dp
private val HANDLE_HEIGHT = 4.dp
