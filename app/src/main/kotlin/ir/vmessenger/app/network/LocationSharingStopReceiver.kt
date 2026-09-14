package ir.vmessenger.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.location.LocationService
import ir.vmessenger.data.network.LocationSharingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The «توقف» action on the location-sharing notification.
 *
 * It lives here, rather than being handled inside the service, because stopping the service is
 * only a third of stopping a share: the `location_share` rows have to be marked inactive and the
 * peers have to be told. Handling the action in the service did none of that, so the switch still
 * read "on", the peers still believed they were being followed, and the next start restored it.
 *
 * `:core:location` cannot reach [LocationSharingCoordinator] — it lives in `:data`, which depends
 * on `:core:location` — so the notification broadcasts and this module, which sees both, answers.
 */
class LocationSharingStopReceiver : BroadcastReceiver() {
    /** Hilt entry point rather than `@AndroidEntryPoint`: a receiver cannot call the generated `super.onReceive`. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CoordinatorEntryPoint {
        fun locationSharingCoordinator(): LocationSharingCoordinator
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocationService.ACTION_STOP_SHARING) return
        AppLogger.info(TAG, "stop requested from the notification")
        val coordinator = EntryPointAccessors
            .fromApplication(context.applicationContext, CoordinatorEntryPoint::class.java)
            .locationSharingCoordinator()
        val pendingResult = goAsync()
        scope.launch {
            try {
                coordinator.stopAllSharing()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Location"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(TAG))
    }
}
