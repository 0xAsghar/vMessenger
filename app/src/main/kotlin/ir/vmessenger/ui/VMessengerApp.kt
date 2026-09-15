package ir.vmessenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import ir.vmessenger.core.designsystem.theme.RtlLayout
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.navigation.VMessengerNavHost
import ir.vmessenger.navigation.VmRoute
import ir.vmessenger.ui.contact.ContactRequestOverlay
import ir.vmessenger.ui.network.AppAlertBanner

/**
 * Everything the activity draws.
 *
 * Insets: `enableEdgeToEdge()` is on, and nothing is padded here. Each region
 * consumes its own inset instead — the M3 top app bar takes the status bar, the
 * bottom navigation bar takes the navigation bar, the conversation composer takes
 * the navigation bar plus the IME, and full-bleed screens opt out with
 * `WindowInsets(0)` and pad their floating controls with `safeDrawingPadding()`.
 *
 * [startRoute] is `null` only while the start destination is still being resolved;
 * the system splash screen covers that window, so nothing is drawn in its place.
 */
@Composable
@Suppress("LongParameterList") // the app root: one parameter per thing the whole window depends on
fun VMessengerApp(
    darkTheme: Boolean,
    startRoute: VmRoute?,
    pendingConversationId: String?,
    onPendingConversationHandled: () -> Unit,
    locked: Boolean = false,
    lockContent: @Composable () -> Unit = {},
) {
    RtlLayout {
        VMessengerTheme(darkTheme = darkTheme) {
            // Root surface guarantees a themed background behind every screen;
            // bare-Column screens otherwise show the window background, which may
            // not match the in-app theme choice.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Nothing but the lock composes while locked, and that is a correctness
                    // requirement rather than a tidiness one. These siblings of the NavHost own
                    // view models that reach a DAO, and under strict app lock building a DAO
                    // builds the database, whose passphrase is behind an authentication that has
                    // not happened yet — it threw, and the activity died before it could draw the
                    // very screen the user needed to authenticate with. Drawing them over the lock
                    // would also have shown incoming contact requests to whoever picked the phone
                    // up, which is why they are siblings and not a navigation destination.
                    if (!locked) {
                        NotificationPermissionEffect()
                        ContactRequestOverlay()
                        if (startRoute != null) {
                            VMessengerNavHost(
                                startRoute = startRoute,
                                pendingConversationId = pendingConversationId,
                                onPendingConversationHandled = onPendingConversationHandled,
                            )
                        }
                        AppAlertBanner(modifier = Modifier.align(Alignment.TopCenter))
                    }
                    if (locked) lockContent()
                }
            }
        }
    }
}

/** Asks for POST_NOTIFICATIONS once on Android 13+; a refusal is not fatal. */
@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
