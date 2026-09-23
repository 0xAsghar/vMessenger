package ir.vmessenger.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.vmessenger.core.designsystem.theme.VmTheme

/** A hairline between groups. Present, never loud: dividers mark structure, they are not decoration. */
@Composable
fun VmDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = VmTheme.colors.borderSubtle,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color),
    )
}

/** The vertical form of [VmDivider]. */
@Composable
fun VmVerticalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = VmTheme.colors.borderSubtle,
) {
    Box(
        modifier
            .fillMaxHeight()
            .width(thickness)
            .background(color),
    )
}
