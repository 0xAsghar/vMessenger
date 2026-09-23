package ir.vmessenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ir.vmessenger.app.locale.AppLocaleController
import ir.vmessenger.core.common.text.BidiText
import ir.vmessenger.core.designsystem.component.VmSurface
import ir.vmessenger.core.designsystem.theme.RtlLayout
import ir.vmessenger.core.designsystem.theme.VMessengerTheme
import ir.vmessenger.core.designsystem.theme.VmTheme
import ir.vmessenger.core.notifications.CallNotificationManager
import ir.vmessenger.data.call.CallEnd
import ir.vmessenger.data.call.CallEndReason
import ir.vmessenger.ui.appDarkTheme
import ir.vmessenger.ui.call.CallActions
import ir.vmessenger.ui.call.CallScreen
import ir.vmessenger.ui.call.CallViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

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
class CallActivity : AppCompatActivity() {
    private val viewModel: CallViewModel by viewModels()

    @Inject
    lateinit var appLocaleController: AppLocaleController

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.recordMicrophoneAnswer(granted)
        if (granted) {
            viewModel.accept()
        } else {
            // Nothing to answer with: a call in which this side cannot be heard is not answered.
            Toast.makeText(this, R.string.call_needs_microphone, Toast.LENGTH_LONG).show()
            viewModel.decline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An AppCompatActivity for this reason alone: below Android 13 the per-app language is
        // applied only to AppCompat activities, and a ComponentActivity would ring in the device's.
        appLocaleController.onActivityConfiguration(resources.configuration)
        showOverLockScreen()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val actions = CallActions(
            onAccept = ::answer,
            onDecline = { viewModel.decline() },
            onHangUp = { viewModel.hangUp() },
            onToggleMute = viewModel::setMuted,
            onToggleSpeaker = viewModel::setSpeaker,
        )

        setContent {
            val session by viewModel.session.collectAsStateWithLifecycle()
            val avatarSeed by viewModel.avatarSeed.collectAsStateWithLifecycle()
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            // The app's theme, not the phone's: this screen alone followed the system setting, so a
            // dark app on a light phone rang in white. Nothing is drawn for the moment the setting
            // takes to read, rather than a frame in the wrong theme.
            theme?.let { choice ->
                val darkTheme = appDarkTheme(choice.dark)
                RtlLayout {
                    VMessengerTheme(darkTheme = darkTheme) {
                        VmSurface(color = VmTheme.colors.bgCanvas) {
                            session?.let { live ->
                                CallScreen(session = live, avatarSeed = avatarSeed, actions = actions)
                            }
                        }
                    }
                }
            }
        }

        // The call is over — by either end — so the screen goes with it. Collected on the activity's
        // own scope rather than in composition: a finish() driven from a composable would depend on
        // the window still being composed, and this must happen even as it is going away.
        observeCallEnd()
        if (savedInstanceState == null) answerIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        answerIfAsked(intent)
    }

    /**
     * Answers, asking for the microphone first — the same rule as dialling. Answering used to go
     * straight through: the call connected, the microphone service was refused for want of the
     * permission, and this side was never heard.
     */
    private fun answer() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.accept() else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** The notification's answer button, when the microphone still had to be asked for. */
    private fun answerIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(CallNotificationManager.EXTRA_ANSWER, false) != true) return
        // Once: a recreated screen must not answer a second call with the first one's intent.
        intent.removeExtra(CallNotificationManager.EXTRA_ANSWER)
        answer()
    }

    private fun observeCallEnd() {
        lifecycleScope.launch {
            viewModel.ended.collect(::sayWhy)
        }
        lifecycleScope.launch {
            viewModel.session.collect { session ->
                if (session == null || !session.state.onScreen) finish()
            }
        }
    }

    /**
     * Why the call ended, as the screen goes. Worded through this activity, which carries the app's
     * language below Android 13 where the application context does not; shown on the application
     * context, so it outlives the finish that follows.
     */
    private fun sayWhy(end: CallEnd) {
        val name = BidiText.isolate(end.peerName)
        val text = when (end.reason) {
            CallEndReason.Unreachable -> getString(R.string.call_end_unreachable, name)
            CallEndReason.Declined -> getString(R.string.call_end_declined, name)
            CallEndReason.Busy -> getString(R.string.call_end_busy, name)
            CallEndReason.NoAnswer -> getString(R.string.call_end_no_answer, name)
            CallEndReason.Failed -> getString(R.string.call_end_failed)
        }
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
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
