package ir.vmessenger

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.notifications.MessageNotificationManager
import ir.vmessenger.ui.VMessengerApp
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // The system splash stays up until the start destination is known, so the
        // first composed frame is already the right screen — no in-app splash and
        // no artificial delay.
        splashScreen.setKeepOnScreenCondition { viewModel.startRoute.value == null }
        enableEdgeToEdge()
        // Secure by default: the flag is set before any content is drawn so the
        // first frame can never reach a screenshot, the recents thumbnail or a
        // screen recorder. It is cleared only if the user turned the setting off.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        observeScreenSecurity()
        handleDeepLink(intent)

        setContent {
            val darkThemePref by viewModel.darkTheme.collectAsStateWithLifecycle()
            val startRoute by viewModel.startRoute.collectAsStateWithLifecycle()
            val pendingConversationId by viewModel.pendingConversationId.collectAsStateWithLifecycle()

            VMessengerApp(
                darkTheme = darkThemePref ?: isSystemInDarkTheme(),
                startRoute = startRoute,
                pendingConversationId = pendingConversationId,
                onPendingConversationHandled = viewModel::consumePendingConversation,
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
                viewModel.screenSecurityEnabled.collect { enabled ->
                    if (enabled) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }
}
