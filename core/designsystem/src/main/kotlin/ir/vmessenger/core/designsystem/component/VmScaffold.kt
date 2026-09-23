package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.MutableWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmTheme

/**
 * The layout every screen sits in: a top bar, a bottom bar, a floating action button and a snackbar
 * host, all optional, around the content.
 *
 * The content is given the whole area and a [PaddingValues] saying how much of it the bars cover,
 * so a list can scroll *under* a bar rather than stopping at it. Where a bar is absent, the padding
 * on that side is the system inset instead, which is how a screen without a bottom bar still keeps
 * its last row clear of the gesture handle. The FAB sits at the reading end — bottom-left in
 * Persian — above the bottom bar; a snackbar appears above both.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList", "LongMethod") // One slot per region; the measure pass reads in order.
fun VmScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    containerColor: Color = VmTheme.colors.bgCanvas,
    contentColor: Color = VmTheme.colors.textPrimary,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars,
    content: @Composable (PaddingValues) -> Unit,
) {
    // Only what is still owed: a shell around this scaffold that already padded for an inset — the
    // tab bar for the navigation bar, a banner for the status bar — has consumed it, and counting
    // it again left a band of empty canvas the size of the bar.
    val insets = remember(contentWindowInsets) { MutableWindowInsets(contentWindowInsets) }
    VmSurface(
        modifier = modifier.onConsumedWindowInsetsChanged { consumed ->
            insets.insets = contentWindowInsets.exclude(consumed)
        },
        color = containerColor,
        contentColor = contentColor,
    ) {
        SubcomposeLayout { constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val loose = constraints.copy(minWidth = 0, minHeight = 0)

            val top = subcompose(ScaffoldSlot.Top, topBar).map { it.measure(loose) }
            val topHeight = top.tallest()
            val bottom = subcompose(ScaffoldSlot.Bottom, bottomBar).map { it.measure(loose) }
            val bottomHeight = bottom.tallest()
            val fab = subcompose(ScaffoldSlot.Fab, floatingActionButton).map { it.measure(loose) }
            val fabWidth = fab.widest()
            val fabHeight = fab.tallest()
            val snack = subcompose(ScaffoldSlot.Snackbar, snackbarHost).map { it.measure(loose) }
            val snackWidth = snack.widest()
            val snackHeight = snack.tallest()

            val insetTop = insets.getTop(this)
            val insetBottom = insets.getBottom(this)
            val ltr = layoutDirection == LayoutDirection.Ltr
            val insetLeft = insets.getLeft(this, layoutDirection)
            val insetRight = insets.getRight(this, layoutDirection)
            val insetStart = if (ltr) insetLeft else insetRight
            val insetEnd = if (ltr) insetRight else insetLeft
            val coveredTop = if (topHeight > 0) topHeight else insetTop
            val coveredBottom = if (bottomHeight > 0) bottomHeight else insetBottom
            val padding = PaddingValues(
                start = insetStart.toDp(),
                top = coveredTop.toDp(),
                end = insetEnd.toDp(),
                bottom = coveredBottom.toDp(),
            )
            val body = subcompose(ScaffoldSlot.Body) { content(padding) }
                .map { it.measure(Constraints.fixed(width, height)) }

            val margin = FAB_MARGIN.roundToPx()
            val fabBottomEdge = height - coveredBottom - margin
            layout(width, height) {
                body.forEach { it.place(0, 0) }
                top.forEach { it.place(0, 0) }
                bottom.forEach { it.place(0, height - bottomHeight) }
                // placeRelative: an x measured from the start edge, mirrored in RTL.
                fab.forEach { it.placeRelative(width - fabWidth - margin - insetEnd, fabBottomEdge - fabHeight) }
                val snackBottom = if (fabHeight > 0) fabBottomEdge - fabHeight - margin else height - coveredBottom
                snack.forEach { it.place((width - snackWidth) / 2, snackBottom - snackHeight) }
            }
        }
    }
}

private enum class ScaffoldSlot { Top, Bottom, Fab, Snackbar, Body }

/** An empty slot measures as nothing, which is what lets every region be optional. */
private fun List<Placeable>.tallest(): Int = maxOfOrNull { it.height } ?: 0

private fun List<Placeable>.widest(): Int = maxOfOrNull { it.width } ?: 0

private val FAB_MARGIN = 16.dp
