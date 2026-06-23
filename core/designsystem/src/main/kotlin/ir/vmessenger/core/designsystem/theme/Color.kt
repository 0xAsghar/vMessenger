package ir.vmessenger.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val InkBlack = Color(0xFF0A0A0A)
private val InkWhite = Color(0xFFF5F5F5)
private val GraySecondary = Color(0xFF5C5C5C)
private val GraySecondaryDark = Color(0xFFA3A3A3)
private val SurfaceLight = Color(0xFFFAFAFA)
private val SurfaceVariantLight = Color(0xFFF2F2F2)
private val OutlineLight = Color(0xFFE0E0E0)
private val BackgroundDark = Color(0xFF0B0B0B)
private val SurfaceDark = Color(0xFF121212)
private val SurfaceVariantDark = Color(0xFF1C1C1C)
private val OutlineDark = Color(0xFF2A2A2A)
private val GraphiteAccent = Color(0xFF3A3A3A)
private val SuccessMuted = Color(0xFF4A6B55)
private val ErrorMuted = Color(0xFF7A4A4A)

private val LightColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = Color.White,
    secondary = GraphiteAccent,
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = InkBlack,
    surface = Color.White,
    onSurface = InkBlack,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = GraySecondary,
    outline = OutlineLight,
    error = ErrorMuted,
    onError = Color.White,
    tertiary = SuccessMuted,
)

private val DarkColorScheme = darkColorScheme(
    primary = InkWhite,
    onPrimary = InkBlack,
    secondary = GraySecondaryDark,
    onSecondary = InkBlack,
    background = BackgroundDark,
    onBackground = InkWhite,
    surface = SurfaceDark,
    onSurface = InkWhite,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = GraySecondaryDark,
    outline = OutlineDark,
    error = ErrorMuted,
    onError = InkWhite,
    tertiary = SuccessMuted,
)

@Composable
fun VMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VMessengerTypography,
        shapes = VMessengerShapes,
        content = content,
    )
}
