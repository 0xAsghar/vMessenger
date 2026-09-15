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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import ir.vmessenger.core.designsystem.LocalAppObscured
import ir.vmessenger.core.designsystem.theme.RtlLayout
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.data.lock.LockState
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
    lockState: LockState = LockState.Unlocked,
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
                    NotificationPermissionEffect()
                    CompositionLocalProvider(LocalAppObscured provides (lockState != LockState.Unlocked)) {
                        // The contact-request overlay is the one sibling of the NavHost whose view
                        // model reaches a DAO, and building a DAO while the lock holds the passphrase
                        // is what crashed the activity before it could draw the lock. It is also the
                        // one nobody should see over a lock screen, which is why it is a sibling and
                        // not a route — so the same line answers both.
                        if (lockState == LockState.Unlocked) ContactRequestOverlay()
                        // A soft lock keeps the graph: it covers the screen, and tearing the NavHost
                        // down would throw away the user's place, an unsaved draft and every view
                        // model behind them, to protect a database that a soft lock never shuts. A
                        // strict lock does shut it, and its screens read from it, so there the graph
                        // goes. On a cold start this is moot — startRoute stays null until the unlock
                        // resolves it, so nothing composes either way.
                        if (startRoute != null && lockState != LockState.LockedStrict) {
                            VMessengerNavHost(
                                startRoute = startRoute,
                                pendingConversationId = pendingConversationId,
                                onPendingConversationHandled = onPendingConversationHandled,
                            )
                        }
                    }
                    AppAlertBanner(modifier = Modifier.align(Alignment.TopCenter))
                    if (lockState != LockState.Unlocked) {
                        // Opaque, and drawn while the state is still Undetermined as well as when
                        // it is decided. Whether to lock is an asynchronous answer; the first frame
                        // after a resume is not, and without something over it that frame is the
                        // conversation the user had open. The navigation graph underneath stays
                        // composed, so nothing is lost when the answer is "no lock needed".
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {}
                        lockContent()
                    }
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
