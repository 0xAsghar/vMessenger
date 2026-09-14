package ir.vmessenger.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light roles -----------------------------------------------------------------
private val LightPrimary = Color(0xFF0F766E)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD7F1EC)
private val LightOnPrimaryContainer = Color(0xFF05302B)
private val LightSecondary = Color(0xFF3A3A3A)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFE9E9E9)
private val LightOnSecondaryContainer = Color(0xFF0A0A0A)
private val LightTertiary = Color(0xFF4A6B55)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFDCEBE0)
private val LightOnTertiaryContainer = Color(0xFF10261A)
private val LightError = Color(0xFF8C3B3B)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFF6DADA)
private val LightOnErrorContainer = Color(0xFF3B0F0F)
private val LightBackground = Color(0xFFFAFAFA)
private val LightOnBackground = Color(0xFF0A0A0A)
private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF0A0A0A)
private val LightSurfaceVariant = Color(0xFFF2F2F2)
private val LightOnSurfaceVariant = Color(0xFF5C5C5C)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF5F5F5)
private val LightSurfaceContainer = Color(0xFFEFEFEF)
private val LightSurfaceContainerHigh = Color(0xFFE9E9E9)
private val LightSurfaceContainerHighest = Color(0xFFE3E3E3)
private val LightSurfaceBright = Color(0xFFFAFAFA)
private val LightSurfaceDim = Color(0xFFDADADA)
private val LightInverseSurface = Color(0xFF1C1C1C)
private val LightInverseOnSurface = Color(0xFFF5F5F5)
private val LightInversePrimary = Color(0xFF4FD1BE)
private val LightOutline = Color(0xFFC7C7C7)
private val LightOutlineVariant = Color(0xFFE0E0E0)

// Dark roles ------------------------------------------------------------------
private val DarkPrimary = Color(0xFF4FD1BE)
private val DarkOnPrimary = Color(0xFF00332D)
private val DarkPrimaryContainer = Color(0xFF11423C)
private val DarkOnPrimaryContainer = Color(0xFFD7F1EC)
private val DarkSecondary = Color(0xFFA3A3A3)
private val DarkOnSecondary = Color(0xFF0A0A0A)
private val DarkSecondaryContainer = Color(0xFF2A2A2A)
private val DarkOnSecondaryContainer = Color(0xFFF5F5F5)
private val DarkTertiary = Color(0xFF8FB89E)
private val DarkOnTertiary = Color(0xFF0F2A19)
private val DarkTertiaryContainer = Color(0xFF2A4634)
private val DarkOnTertiaryContainer = Color(0xFFDCEBE0)
private val DarkError = Color(0xFFE39A9A)
private val DarkOnError = Color(0xFF3B0F0F)
private val DarkErrorContainer = Color(0xFF5A2323)
private val DarkOnErrorContainer = Color(0xFFF6DADA)
private val DarkBackground = Color(0xFF0B0B0B)
private val DarkOnBackground = Color(0xFFF5F5F5)
private val DarkSurface = Color(0xFF121212)
private val DarkOnSurface = Color(0xFFF5F5F5)
private val DarkSurfaceVariant = Color(0xFF1C1C1C)
private val DarkOnSurfaceVariant = Color(0xFFA3A3A3)
private val DarkSurfaceContainerLowest = Color(0xFF0B0B0B)
private val DarkSurfaceContainerLow = Color(0xFF161616)
private val DarkSurfaceContainer = Color(0xFF1C1C1C)
private val DarkSurfaceContainerHigh = Color(0xFF242424)
private val DarkSurfaceContainerHighest = Color(0xFF2E2E2E)
private val DarkSurfaceBright = Color(0xFF2E2E2E)
private val DarkSurfaceDim = Color(0xFF0B0B0B)
private val DarkInverseSurface = Color(0xFFF5F5F5)
private val DarkInverseOnSurface = Color(0xFF0A0A0A)
private val DarkInversePrimary = Color(0xFF0F766E)
private val DarkOutline = Color(0xFF3A3A3A)
private val DarkOutlineVariant = Color(0xFF2A2A2A)

private val Scrim = Color(0xFF000000)

/** Muted, mutually distinguishable hues for group sender names and identicons (light theme). */
private val LightSenderPalette = listOf(
    Color(0xFF9A5B2E),
    Color(0xFF8A4B6B),
    Color(0xFF4A5E8C),
    Color(0xFF3F6B5A),
    Color(0xFF7A5C9E),
    Color(0xFF8C6A2E),
    Color(0xFF2F6E7A),
    Color(0xFF8C4A4A),
)

/** Same eight hues lightened so they stay readable on the dark chat background. */
private val DarkSenderPalette = listOf(
    Color(0xFFD9A579),
    Color(0xFFD69CBE),
    Color(0xFF9DB3E0),
    Color(0xFF8FC3AE),
    Color(0xFFC3AEE0),
    Color(0xFFD9C07E),
    Color(0xFF86C6D1),
    Color(0xFFDDA0A0),
)

val VmLightColorScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Scrim,
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest,
)

val VmDarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Scrim,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
)

/**
 * Semantic colours that have no Material 3 role: chat bubbles, delivery ticks, the
 * recording indicator and the per-sender palette. Reach them through [MaterialTheme.vm].
 */
@Immutable
@Suppress("LongParameterList") // A token bundle: every colour is an independent design decision.
class VmColors(
    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    val bubbleIncoming: Color,
    val onBubbleIncoming: Color,
    val tickPending: Color,
    val tickSent: Color,
    val tickRead: Color,
    val chatBackground: Color,
    val senderPalette: List<Color>,
    val recordingRed: Color,
    val keyChangeWarning: Color,
    /** Briefly tints a bubble that was just jumped to, so the eye can find it. */
    val bubbleHighlight: Color,
) {
    /** Stable colour for a sender, derived from its identity hash. */
    fun senderColor(seed: ByteArray): Color {
        if (senderPalette.isEmpty()) return onBubbleIncoming
        var acc = 0
        for (byte in seed) {
            acc = (acc * 31 + (byte.toInt() and 0xFF)) and 0x7FFFFFFF
        }
        return senderPalette[acc % senderPalette.size]
    }
}

val VmLightColors = VmColors(
    bubbleOutgoing = LightPrimaryContainer,
    onBubbleOutgoing = LightOnPrimaryContainer,
    bubbleIncoming = LightSurface,
    onBubbleIncoming = LightOnSurface,
    tickPending = LightOnSurfaceVariant,
    tickSent = LightOnSurfaceVariant,
    tickRead = LightPrimary,
    chatBackground = LightSurfaceContainerLow,
    senderPalette = LightSenderPalette,
    recordingRed = Color(0xFFD9534F),
    keyChangeWarning = LightTertiaryContainer,
    bubbleHighlight = LightTertiaryContainer,
)

val VmDarkColors = VmColors(
    bubbleOutgoing = DarkPrimaryContainer,
    onBubbleOutgoing = DarkOnPrimaryContainer,
    bubbleIncoming = DarkSurfaceContainerHigh,
    onBubbleIncoming = DarkOnSurface,
    tickPending = DarkOnSurfaceVariant,
    tickSent = DarkOnSurfaceVariant,
    tickRead = DarkPrimary,
    chatBackground = DarkSurfaceContainerLow,
    senderPalette = DarkSenderPalette,
    recordingRed = Color(0xFFE57373),
    keyChangeWarning = DarkTertiaryContainer,
    bubbleHighlight = DarkTertiaryContainer,
)

val LocalVmColors = staticCompositionLocalOf { VmLightColors }

/** `MaterialTheme.vm.bubbleOutgoing` and friends. */
val MaterialTheme.vm: VmColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVmColors.current
