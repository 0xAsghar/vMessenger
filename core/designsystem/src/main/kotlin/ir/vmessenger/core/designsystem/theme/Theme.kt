package ir.vmessenger.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

@Composable
fun RtlLayout(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        content = content,
    )
}
