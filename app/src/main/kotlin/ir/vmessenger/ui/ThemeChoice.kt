package ir.vmessenger.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toArgb
import ir.vmessenger.core.datastore.ThemeMode
import ir.vmessenger.core.datastore.ThemePreferences
import ir.vmessenger.core.designsystem.theme.VmDarkColors
import ir.vmessenger.core.designsystem.theme.VmLightColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The theme setting once read: [dark] is a fixed choice, or null to follow the phone. */
@Immutable
internal data class ThemeChoice(val dark: Boolean?)

internal fun ThemePreferences.themeChoice(): Flow<ThemeChoice> = themeMode.map { mode ->
    ThemeChoice(
        dark = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> null
        },
    )
}

/**
 * [choice] resolved against the phone's setting, with this activity's system bars kept in step.
 *
 * The bars follow the app's theme, not the phone's. Left to the default they read the system
 * setting, so the app in Dark on a light phone drew dark status icons on its dark canvas —
 * invisible — over a light band where the navigation buttons sit. Every activity that draws the
 * app's theme resolves it here, so none of them can follow the phone on its own again.
 */
@Composable
internal fun ComponentActivity.appDarkTheme(choice: Boolean?): Boolean {
    val dark = choice ?: isSystemInDarkTheme()
    DisposableEffect(dark) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NavScrimLight, NavScrimDark) { dark },
        )
        onDispose {}
    }
    return dark
}

/**
 * Behind three-button navigation: the app's own canvas, nearly opaque, so the buttons sit on the
 * same colour as the tab bar above them instead of on a band of their own.
 */
private val NavScrimLight = VmLightColors.bgCanvas.copy(alpha = NAV_SCRIM_ALPHA).toArgb()
private val NavScrimDark = VmDarkColors.bgCanvas.copy(alpha = NAV_SCRIM_ALPHA).toArgb()
private const val NAV_SCRIM_ALPHA = 0.9f
