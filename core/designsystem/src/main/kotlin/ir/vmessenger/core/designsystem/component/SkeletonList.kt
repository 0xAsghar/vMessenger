package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmShapes
import ir.vmessenger.core.designsystem.theme.VmSizes
import ir.vmessenger.core.designsystem.theme.VmSpacing
import ir.vmessenger.core.designsystem.theme.VmTheme

private val TitleBarHeight = 14.dp
private val SubtitleBarHeight = 12.dp
private const val TITLE_WIDTH_FRACTION = 0.45f
private const val SUBTITLE_WIDTH_FRACTION = 0.7f

/**
 * Static placeholder rows shown while a list loads. Deliberately shimmer-free: an animation
 * that runs on every cold start costs more than it communicates.
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 8,
    itemHeight: Dp = VmSizes.listItemHeight,
) {
    Column(modifier = modifier.clearAndSetSemantics { }) {
        repeat(rows) {
            SkeletonRow(itemHeight)
        }
    }
}

@Composable
private fun SkeletonRow(itemHeight: Dp) {
    val placeholder = VmTheme.colors.bgSubtleStrong
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight)
            .padding(horizontal = VmSpacing.lg, vertical = VmSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VmSpacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(VmSizes.avatarMd)
                .clip(CircleShape)
                .background(placeholder),
        )
        Column(verticalArrangement = Arrangement.spacedBy(VmSpacing.sm)) {
            Bar(placeholderHeight = TitleBarHeight, widthFraction = TITLE_WIDTH_FRACTION)
            Bar(placeholderHeight = SubtitleBarHeight, widthFraction = SUBTITLE_WIDTH_FRACTION)
        }
    }
}

@Composable
private fun Bar(placeholderHeight: Dp, widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(placeholderHeight)
            .clip(VmShapes.pill)
            .background(VmTheme.colors.bgSubtle),
    )
}
