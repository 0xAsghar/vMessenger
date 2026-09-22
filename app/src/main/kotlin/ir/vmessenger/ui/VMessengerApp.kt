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
import androidx.navigation.compose.rememberNavController
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
 * the splash covers that window only while the app is unlocked — under a strict lock the route
 * never resolves, and what is drawn is the lock screen.
 */
@Composable
@Suppress("LongParameterList") // the app root: one parameter per thing the whole window depends on
fun VMessengerApp(
    darkTheme: Boolean,
    startRoute: VmRoute?,
    pendingConversationId: String?,
    onPendingConversationHandled: () -> Unit,
    shareWaiting: Boolean = false,
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
                    // Provided around everything, deliberately. Inside the gate below it could
                    // only ever be observed as false — the subtree is composed only when unlocked —
                    // so the four guards that read it were dead code that looked live.
                    CompositionLocalProvider(LocalAppObscured provides (lockState != LockState.Unlocked)) {
                        // The contact-request overlay is the one sibling of the NavHost whose view
                        // model reaches a DAO, and building a DAO while the lock holds the passphrase
                        // is what crashed the activity before it could draw the lock. It is also the
                        // one nobody should see over a lock screen, which is why it is a sibling and
                        // not a route — so the same line answers both.
                        if (lockState == LockState.Unlocked) ContactRequestOverlay()
                        // Nothing of the app composes while it is *locked* — not "nothing that
                        // reads the database", nothing at all. [LockState.Undetermined] is not a
                        // lock, though: it is the cover thrown up the moment the app leaves the
                        // foreground, before the timeout has decided anything, and tearing the
                        // graph down for it meant every home-press rebuilt every screen and threw
                        // away scroll positions, open pickers and half-typed input. The opaque
                        // Surface below covers that case, and a dialog cannot be interacted with
                        // through it in the moment before the answer lands.
                        //
                        // The previous shape kept the graph alive through a soft lock and guarded
                        // the three shared dialog wrappers instead. That was whack-a-mole, and it
                        // lost: eight dialogs and sheets never went through those wrappers, each
                        // one its own window above the activity's content and so above the lock
                        // overlay too. One of them was the PIN-setup dialog — whoever held the
                        // phone could set a new PIN on top of the lock screen and then walk in
                        // through the lock screen underneath with it. A guard that has to be
                        // remembered at every call site is not a lock.
                        //
                        // The user's place survives anyway: this NavController is remembered above
                        // the gate and owns the back stack, so the host leaving composition does
                        // not pop it. Left as the host's own default parameter it was discarded
                        // with the host, and navigation-compose destroys nothing on dispose — so
                        // every lock cycle abandoned a graph's worth of ViewModelStores, including
                        // the one whose onCleared zeroes a staged backup passphrase.
                        val navController = rememberNavController()
                        val locked =
                            lockState == LockState.Locked || lockState == LockState.LockedStrict
                        if (startRoute != null && !locked) {
                            VMessengerNavHost(
                                startRoute = startRoute,
                                pendingConversationId = pendingConversationId,
                                onPendingConversationHandled = onPendingConversationHandled,
                                shareWaiting = shareWaiting,
                                navController = navController,
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
