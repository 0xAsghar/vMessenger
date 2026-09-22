package ir.vmessenger

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.core.designsystem.theme.RtlLayout
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.ui.call.CallActions
import ir.vmessenger.ui.call.CallScreen
import ir.vmessenger.ui.call.CallViewModel
import kotlinx.coroutines.launch

/**
 * The call screen, in its own activity.
 *
 * Separate from [MainActivity] for one reason: a ringing call has to be answerable from a locked
 * screen, and that means an activity that shows over the keyguard. Putting the whole app there
 * would put the whole app over the keyguard.
 *
 * What it does *not* do is bypass the app lock. This activity shows a name, a state and the call
 * buttons — no conversations, no contacts, no history. Answering a call is the one thing a locked
 * phone should allow, and it is the only thing offered here.
 *
 * `FLAG_SECURE` is set unconditionally, unlike [MainActivity] where the user may turn it off: a
 * screenshot of this screen is a record of who called whom and when.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {
    private val viewModel: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val actions = CallActions(
            onAccept = { viewModel.accept() },
            onDecline = { viewModel.decline() },
            onHangUp = { viewModel.hangUp() },
            onToggleMute = viewModel::setMuted,
        )

        setContent {
            val session by viewModel.session.collectAsStateWithLifecycle()
            RtlLayout {
                VMessengerTheme(darkTheme = isSystemInDarkTheme()) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        session?.let { live ->
                            CallScreen(session = live, actions = actions)
                        }
                    }
                }
            }
        }

        // The call is over — by either end — so the screen goes with it. Collected on the activity's
        // own scope rather than in composition: a finish() driven from a composable would depend on
        // the window still being composed, and this must happen even as it is going away.
        observeCallEnd()
    }

    private fun observeCallEnd() {
        lifecycleScope.launch {
            viewModel.session.collect { session ->
                if (session == null || !session.state.onScreen) finish()
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }
}
