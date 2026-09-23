package ir.vmessenger.core.designsystem.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import ir.vmessenger.core.common.text.VmLocale
import ir.vmessenger.core.designsystem.foundation.LocalVmContentColor
import ir.vmessenger.core.designsystem.foundation.LocalVmTextStyle
import ir.vmessenger.core.designsystem.foundation.VmIndication

/** Where the app's tokens are read: `VmTheme.colors.textSecondary`, `VmTheme.typography.bodyLg`. */
object VmTheme {
    val colors: VmColors
        @Composable
        @ReadOnlyComposable
        get() = LocalVmColors.current

    val typography: VmTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalVmTypography.current
}

/**
 * The app's theme. Provides the tokens, the default content colour and text style every text and
 * icon inherits, the press indication, and the text-selection handles in the accent.
 *
 * There is no Material underneath. Every component the app draws is its own, built on Compose
 * Foundation, in Element X's visual language rather than Material's: the tokens here are the whole
 * of what a screen can reach for.
 */
@Composable
fun VMessengerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) VmDarkColors else VmLightColors
    val selection = TextSelectionColors(
        handleColor = colors.textAccent,
        backgroundColor = colors.textAccent.copy(alpha = SELECTION_ALPHA),
    )
    CompositionLocalProvider(
        LocalVmColors provides colors,
        LocalVmTypography provides VmDefaultTypography,
        LocalVmContentColor provides colors.textPrimary,
        LocalVmTextStyle provides VmDefaultTypography.bodyMd,
        LocalTextSelectionColors provides selection,
        LocalIndication provides VmIndication,
        content = content,
    )
}

@Composable
fun RtlLayout(content: @Composable () -> Unit) {
    // Read rather than observed: changing the app language recreates the activity, so composition
    // starts again with the new value. Nothing here needs to react to a change in place.
    val direction = if (VmLocale.current.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalLayoutDirection provides direction,
        content = content,
    )
}

private const val SELECTION_ALPHA = 0.3f
