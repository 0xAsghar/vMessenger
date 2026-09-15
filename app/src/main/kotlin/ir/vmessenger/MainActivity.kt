package ir.vmessenger

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.notifications.MessageNotificationManager
import ir.vmessenger.data.lock.LockState
import ir.vmessenger.feature.lock.AppLockScreen
import ir.vmessenger.ui.VMessengerApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A FragmentActivity rather than a ComponentActivity: `androidx.biometric`'s BiometricPrompt needs
 * one to attach to, and the app lock offers biometric unlock. The theme already parents
 * `Theme.AppCompat.DayNight.NoActionBar`, so nothing else about the window changes.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The system splash stays up until the start destination is known, so the
        // first composed frame is already the right screen — no in-app splash and
        // no artificial delay.
        // Held only while the destination is genuinely unknown *and* the app is not locked. Under
        // strict mode the route cannot be resolved until the user authenticates, so keeping the
        // system splash up would hide the lock screen behind a blank window forever.
        splashScreen.setKeepOnScreenCondition {
            viewModel.lockState.value == LockState.Undetermined ||
                (viewModel.startRoute.value == null && viewModel.lockState.value == LockState.Unlocked)
        }
        enableEdgeToEdge()
        // Secure by default: the flag is set before any content is drawn so the
        // first frame can never reach a screenshot, the recents thumbnail or a
        // screen recorder. It is cleared only if the user turned the setting off.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        observeScreenSecurity()
        handleDeepLink(intent)
        observeAutoLock()

        setContent {
            val darkThemePref by viewModel.darkTheme.collectAsStateWithLifecycle()
            val startRoute by viewModel.startRoute.collectAsStateWithLifecycle()
            val pendingConversationId by viewModel.pendingConversationId.collectAsStateWithLifecycle()

            val lockState by viewModel.lockState.collectAsStateWithLifecycle()

            VMessengerApp(
                darkTheme = darkThemePref ?: isSystemInDarkTheme(),
                startRoute = startRoute,
                // Held back while locked. A notification tap would otherwise have the NavHost open
                // the conversation underneath the lock screen, which is the one place the gate is
                // easiest to walk around.
                pendingConversationId = pendingConversationId.takeIf { lockState == LockState.Unlocked },
                onPendingConversationHandled = viewModel::consumePendingConversation,
                lockState = lockState,
                // Nothing while the state is still [LockState.Undetermined]: the app content is
                // already held back by `locked`, and drawing the lock there would flash a PIN
                // screen at users who have never set one. The splash covers this window.
                lockContent = { if (lockState != LockState.Undetermined) AppLockScreen(onUnlocked = {}) },
            )
        }
    }

    /**
     * `launchMode=singleTask` means a notification tap on a live process arrives
     * here instead of `onCreate`; [setIntent] keeps `getIntent()` in step for
     * anything that reads it later.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val conversationId = intent?.getStringExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
        // The intent outlives the activity, so the extra is consumed here: without
        // this, every configuration change would reopen the same conversation.
        intent?.removeExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
        viewModel.onDeepLink(conversationId)
    }

    private fun observeScreenSecurity() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The lock forces the flag on regardless of the preference. A user who turned
                // screen security off would otherwise leak a recents thumbnail of whatever was on
                // screen when the app locked — which is exactly what the lock is for.
                combine(viewModel.screenSecurityEnabled, viewModel.lockState) { enabled, lock ->
                    enabled || lock != LockState.Unlocked
                }.collect { secure ->
                    if (secure) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }

    /**
     * Re-arms the lock after the app has been away long enough.
     *
     * Timed on `elapsedRealtime`, never the wall clock: a timeout measured against a clock the
     * user can change is not a timeout.
     */
    private fun observeAutoLock() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> viewModel.onBackgrounded(SystemClock.elapsedRealtime())
                            Lifecycle.Event.ON_START -> viewModel.onForegrounded(SystemClock.elapsedRealtime())
                            else -> Unit
                        }
                    },
                )
            }
        }
    }
}
