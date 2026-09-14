package ir.vmessenger.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
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
        ) {
            // Material's LocalTextStyle defaults to TextStyle.Default, not to the typography above,
            // so a bare Text() with no style argument would keep the hard-RTL paragraph that the
            // vazir() factory exists to avoid. Kept here rather than in RtlLayout so the layout
            // direction and the text direction stay separable concerns.
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                content = content,
            )
        }
    }
}

@Composable
fun RtlLayout(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        content = content,
    )
}
