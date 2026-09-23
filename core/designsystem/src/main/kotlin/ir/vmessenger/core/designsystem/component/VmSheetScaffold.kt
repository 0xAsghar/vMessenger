package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.R
import ir.vmessenger.core.designsystem.theme.VmElevation
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun rememberVmSheetScaffoldState(initiallyExpanded: Boolean = false): VmSheetScaffoldState =
    rememberSaveable(saver = VmSheetScaffoldState.Saver) { VmSheetScaffoldState(initiallyExpanded) }

/**
 * A screen with a sheet that never leaves: it peeks [peekHeight] tall over [content], and pulls up
 * to its full height by drag, by fling, or by a tap on its handle. A list inside it scrolls; pulling
 * that list down past its top brings the sheet back down with it.
 *
 * The content fills the whole screen behind the sheet — a map runs under it, which is the point.
 */
@Composable
fun VmSheetScaffold(
    sheetContent: @Composable ColumnScope.() -> Unit,
    peekHeight: Dp,
    modifier: Modifier = Modifier,
    state: VmSheetScaffoldState = rememberVmSheetScaffoldState(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val peekPx = with(density) { peekHeight.roundToPx() }
    val scope = rememberCoroutineScope()
    val nestedScroll = remember(state, density) { PersistentSheetNestedScroll(state, density) }
    Box(modifier = modifier.fillMaxSize()) {
        content()
        VmSurface(
            shape = VmShapes.sheet,
            color = VmTheme.colors.bgElevated,
            contentColor = VmTheme.colors.textPrimary,
            shadowElevation = VmElevation.sheet,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = MAX_WIDTH)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    state.onMeasured(placeable.height, peekPx)
                    layout(placeable.width, placeable.height) {
                        val offset = state.offset.takeUnless { it.isNaN() } ?: state.collapsedOffset
                        placeable.place(0, offset.roundToInt())
                    }
                }
                .nestedScroll(nestedScroll)
                .draggable(
                    state = rememberDraggableState { delta -> state.dragBy(delta) },
                    orientation = Orientation.Vertical,
                    onDragStarted = { state.stop() },
                    onDragStopped = { velocity ->
                        state.release()
                        state.settle(velocity, density)
                    },
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHandle(
                    expanded = state.expanded,
                    onToggle = { scope.launch { if (state.expanded) state.collapse() else state.expand() } },
                )
                sheetContent()
            }
        }
    }
}

/**
 * The handle: a short bar, and also a button — a tap toggles the sheet, and a screen reader hears
 * which way it will go, since dragging is not something it can do.
 */
@Composable
private fun ColumnScope.SheetHandle(expanded: Boolean, onToggle: () -> Unit) {
    val label = stringResource(if (expanded) R.string.vm_sheet_collapse else R.string.vm_sheet_expand)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label }
            .padding(horizontal = HANDLE_TOUCH_SIDE, vertical = HANDLE_TOUCH_VERTICAL),
    ) {
        Box(
            modifier = Modifier
                .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
                .background(VmTheme.colors.iconTertiary, VmShapes.pill),
        )
    }
}

/**
 * Between the list inside the sheet and the sheet itself: pushing up raises the sheet before the
 * list scrolls, pulling a list that is already at its top lowers the sheet, and a fling that ends
 * with the sheet between its rests is the sheet's to settle.
 */
private class PersistentSheetNestedScroll(
    private val state: VmSheetScaffoldState,
    private val density: Density,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && source == NestedScrollSource.UserInput) {
            Offset(0f, state.dragBy(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f && source == NestedScrollSource.UserInput) {
            Offset(0f, state.dragBy(available.y))
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!state.isBetween) return Velocity.Zero
        state.settle(available.y, density)
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!state.isBetween || abs(available.y) == 0f) return Velocity.Zero
        state.settle(available.y, density)
        return available
    }
}

private val MAX_WIDTH = 640.dp
private val HANDLE_WIDTH = 32.dp
private val HANDLE_HEIGHT = 4.dp
private val HANDLE_TOUCH_SIDE = 24.dp
private val HANDLE_TOUCH_VERTICAL = 10.dp
