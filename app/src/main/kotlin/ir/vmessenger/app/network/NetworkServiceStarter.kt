package ir.vmessenger.app.network

import android.content.Context
import androidx.core.content.ContextCompat
import ir.vmessenger.core.common.logging.AppLogger

private const val TAG = "Network"

/**
 * Starts [NetworkLifecycleService] in the foreground, tolerating the cases where
 * the platform refuses: on Android 12+ a background start throws
 * `ForegroundServiceStartNotAllowedException`, and a boot/worker start can be
 * refused outright. Refusals are logged, never fatal — the sticky service is
 * restarted by the system, and the next app launch starts it explicitly.
 *
 * @return true when the start request was accepted by the platform.
 */
internal fun startNetworkService(context: Context, reason: String): Boolean =
    runCatching {
        ContextCompat.startForegroundService(context, NetworkLifecycleService.startIntent(context))
    }.fold(
        onSuccess = { true },
        onFailure = { throwable ->
            AppLogger.warn(TAG, "service start refused ($reason): $throwable")
            false
        },
    )
