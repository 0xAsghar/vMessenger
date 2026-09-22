package ir.vmessenger.app.call

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.notifications.CallNotificationManager
import ir.vmessenger.data.call.CallCoordinator
import ir.vmessenger.data.call.CallSession
import ir.vmessenger.data.call.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns call state into the two things a call needs outside its own screen: a notification, and a
 * microphone service.
 *
 * It only ever reacts to [CallCoordinator.session] — it cannot start a call, answer one, or open
 * audio. That keeps the decision in one place: whatever this class does, it does because the
 * coordinator already moved the call there, through a signal or a user action.
 *
 * Tied to the network service's lifetime deliberately. The strict app lock stops that service, and
 * a call must not ring while the app is locked shut.
 */
@Singleton
class CallSessionPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callCoordinator: CallCoordinator,
    private val callNotificationManager: CallNotificationManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + loggingExceptionHandler(TAG))
    private var job: Job? = null

    /** What was last presented, so an unchanged state does not re-post or re-start anything. */
    private var presented: String? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            callCoordinator.session.collect { present(it) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        withdraw()
    }

    private fun present(session: CallSession?) {
        val state = session?.state
        if (session == null || state == null || !state.onScreen) {
            withdraw()
            return
        }
        val key = "${session.callId}:$state"
        if (key == presented) return
        presented = key
        if (state == CallState.IncomingRinging) {
            ring(session)
        } else {
            hold(session, connecting = state != CallState.Active)
        }
    }

    private fun ring(session: CallSession) {
        if (!callNotificationManager.canRingFullScreen()) {
            // Revocable from Android 14. The call still arrives, as a heads-up notification rather
            // than taking the screen — worth a line in the log, because "the phone did not ring"
            // is otherwise indistinguishable from a bug.
            AppLogger.info(TAG, "full-screen calls are not permitted; ringing as a notification")
        }
        callNotificationManager.showIncoming(session.callId, session.peerName)
    }

    /**
     * Puts the call under a microphone foreground service.
     *
     * Every path that reaches here followed a user action — dialling, or answering on the screen or
     * from the notification — which is what makes the start permitted. A refusal is not survivable:
     * without the service the platform stops capture as soon as the app is backgrounded, so the
     * call is ended rather than left as a connection nobody can be heard over.
     */
    private fun hold(session: CallSession, connecting: Boolean) {
        callNotificationManager.cancelIncoming()
        val intent = CallForegroundService.startIntent(context, session.callId, session.peerName, connecting)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { failure ->
                AppLogger.warn(TAG, "could not start the microphone service: ${failure.message}")
                scope.launch { callCoordinator.hangUp() }
            }
    }

    private fun withdraw() {
        presented = null
        callNotificationManager.cancelIncoming()
        runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
            .onFailure { AppLogger.warn(TAG, "could not stop the microphone service: ${it.message}") }
    }

    private companion object {
        const val TAG = "Call"
    }
}
