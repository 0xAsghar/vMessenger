package ir.vmessenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.LocalAppObscured
import ir.vmessenger.core.designsystem.component.ConfirmDialog
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.theme.RtlLayout
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.data.lock.LockState
import ir.vmessenger.navigation.VMessengerNavHost
import ir.vmessenger.navigation.VmRoute
import ir.vmessenger.ui.contact.ContactRequestOverlay
import ir.vmessenger.ui.network.LocalAppAlertHost
import ir.vmessenger.ui.network.rememberAppAlertHost

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
    notificationRationalePending: Boolean = false,
    onNotificationRationaleAcknowledged: () -> Unit = {},
    lockState: LockState = LockState.Unlocked,
    lockContent: @Composable () -> Unit = {},
) {
    RtlLayout {
        VMessengerTheme(darkTheme = darkTheme) {
            // Root surface guarantees a themed background behind every screen;
            // bare-Column screens otherwise show the window background, which may
            // not match the in-app theme choice.
            VmSurface(
                modifier = Modifier.fillMaxSize(),
                color = VmTheme.colors.bgCanvas,
            ) {
                // Remembered here, above everything that reads it: the lock gate below explains why
                // it must outlive the host, and the first-run check just below needs its destination.
                val navController = rememberNavController()
                val pastFirstRun = rememberPastFirstRun(navController)
                Box(modifier = Modifier.fillMaxSize()) {
                    // Not over the lock: the explanation names what the app does in the background,
                    // and a dialog is its own window, so it would sit above the lock screen.
                    if (lockState == LockState.Unlocked) {
                        // Not during first run either. The explanation is about delivering messages:
                        // asked on the node screen it read as a demand from an app the user had not
                        // decided to use yet, and asked the moment the identity existed it covered the
                        // one step that shows the user their new ID, with a second "Continue" of its
                        // own on top of that step's.
                        NotificationPermissionEffect(
                            rationalePending = notificationRationalePending && pastFirstRun,
                            onAcknowledged = onNotificationRationaleAcknowledged,
                        )
                    }
                    // Provided around everything, deliberately. Inside the gate below it could
                    // only ever be observed as false — the subtree is composed only when unlocked —
                    // so the four guards that read it were dead code that looked live.
                    // Out here, above the lock gate: the graph is torn down while locked, and a
                    // dismissed alert should stay dismissed when the user comes back.
                    val alertHost = rememberAppAlertHost(
                        // "Notifications are off" only once the user has been asked and answered.
                        // Before that it is simply true of every fresh Android 13+ install, and it
                        // was greeting new users on the first screen, over the text explaining it.
                        notificationAlertAllowed = pastFirstRun && !notificationRationalePending,
                    )
                    CompositionLocalProvider(
                        LocalAppObscured provides (lockState != LockState.Unlocked),
                        LocalAppAlertHost provides alertHost,
                    ) {
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
                    if (lockState != LockState.Unlocked) {
                        // Opaque, and drawn while the state is still Undetermined as well as when
                        // it is decided. Whether to lock is an asynchronous answer; the first frame
                        // after a resume is not, and without something over it that frame is the
                        // conversation the user had open. The navigation graph underneath stays
                        // composed, so nothing is lost when the answer is "no lock needed".
                        VmSurface(
                            modifier = Modifier.fillMaxSize(),
                            color = VmTheme.colors.bgCanvas,
                        ) {}
                        lockContent()
                    }
                }
            }
        }
    }
}

/**
 * Asks for POST_NOTIFICATIONS on Android 13+, but says why first.
 *
 * This permission is load-bearing rather than cosmetic: the foreground-service notice is what keeps
 * the connection alive, so a silent refusal degrades delivery rather than only muting alerts. It
 * used to be sprung as a bare system dialog on the first frame, and re-sprung on every launch while
 * denied — which Android stops showing after two refusals anyway. Now the reason is given once, in
 * the app's own words, and the real platform request follows; a refusal is still not fatal, and the
 * question is not asked again.
 */
@Composable
private fun NotificationPermissionEffect(rationalePending: Boolean, onAcknowledged: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val granted = remember(rationalePending) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
    if (granted || !rationalePending) return
    ConfirmDialog(
        title = stringResource(R.string.notification_permission_title),
        body = stringResource(R.string.notification_permission_body),
        confirmLabel = stringResource(R.string.notification_permission_confirm),
        onConfirm = {
            onAcknowledged()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        // Dismissing is an answer too: it is recorded so the app does not ask again.
        onDismiss = onAcknowledged,
    )
}

/**
 * Whether the user has left first run — the node question and onboarding — for the app proper.
 *
 * Read from the navigation destination rather than from whether an identity exists: the identity
 * is created one step *before* onboarding ends, on the step that shows the user their ID, and
 * "exists" fired there. Until the graph has a destination at all, the answer is no.
 */
@Composable
private fun rememberPastFirstRun(navController: NavHostController): Boolean {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination ?: return false
    return !destination.hasRoute(VmRoute.NodeSetup::class) && !destination.hasRoute(VmRoute.Onboarding::class)
}
