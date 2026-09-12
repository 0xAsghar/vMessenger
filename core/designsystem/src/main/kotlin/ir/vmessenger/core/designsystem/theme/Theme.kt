package ir.vmessenger.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

@Composable
fun VMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) VmDarkColorScheme else VmLightColorScheme
    val vmColors = if (darkTheme) VmDarkColors else VmLightColors

    CompositionLocalProvider(LocalVmColors provides vmColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VMessengerTypography,
            shapes = VMessengerShapes,
            content = content,
        )
    }
}

@Composable
fun RtlLayout(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        content = content,
    )
}
