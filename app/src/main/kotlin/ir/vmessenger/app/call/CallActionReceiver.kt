package ir.vmessenger.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.notifications.CallNotificationManager
import ir.vmessenger.data.call.CallCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The answer, decline and hang-up buttons on the call notification.
 *
 * Answering from here is as explicit as answering on the screen — the user pressed a button that
 * says so — which is what lets the microphone service start on this path at all.
 */
class CallActionReceiver : BroadcastReceiver() {
    /** Hilt entry point rather than `@AndroidEntryPoint`: a receiver cannot call a generated super. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CallEntryPoint {
        fun callCoordinator(): CallCoordinator
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        AppLogger.info(TAG, "notification action $action")
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        // Resolved inside the async window and inside runCatching: the coordinator reaches the
        // database, so under the strict app lock building it throws — and a receiver that throws in
        // onReceive takes the process down with it.
        scope.launch {
            try {
                runCatching { dispatch(appContext, action) }
                    .onFailure { AppLogger.warn(TAG, "call action $action failed: ${it.message}") }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatch(appContext: Context, action: String) {
        val coordinator = EntryPointAccessors
            .fromApplication(appContext, CallEntryPoint::class.java)
            .callCoordinator()
        when (action) {
            CallNotificationManager.ACTION_ACCEPT -> coordinator.accept()
            CallNotificationManager.ACTION_DECLINE -> coordinator.decline()
            else -> coordinator.hangUp()
        }
    }

    private companion object {
        const val TAG = "Call"

        val HANDLED = setOf(
            CallNotificationManager.ACTION_ACCEPT,
            CallNotificationManager.ACTION_DECLINE,
            CallNotificationManager.ACTION_HANG_UP,
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(TAG))
    }
}
