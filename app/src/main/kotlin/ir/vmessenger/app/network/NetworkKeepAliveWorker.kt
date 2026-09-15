package ir.vmessenger.app.network

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.data.network.NetworkCoordinator
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Safety net for the network foreground service: OEM battery managers and the
 * platform itself can kill it without restarting it. Every 15 minutes (the
 * shortest period WorkManager allows) this worker brings messaging back.
 *
 * Restarting the service is the preferred route, but Android 12+ refuses a
 * background foreground-service start unless the app is exempt from battery
 * optimization — which is exactly the situation this worker exists for. When
 * the start is refused the network is brought up **in this process** instead:
 * the relay control channel reconnects and the outbox drains for as long as the
 * worker's process lives. Only when that fails too is the run retried, so
 * WorkManager backs off instead of being told everything is fine.
 */
@HiltWorker
class NetworkKeepAliveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    // Provider: resolving the coordinator builds the database, and a worker constructed while
    // the strict app lock is engaged would throw before doWork() could decide to do nothing.
    private val networkCoordinator: Provider<NetworkCoordinator>,
    private val databaseKeyProvider: DatabaseKeyProvider,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (NetworkLifecycleService.isRunning || nothingToKeepAlive()) return Result.success()
        AppLogger.info(TAG, "network service not running, restarting it")
        return if (startNetworkService(applicationContext, reason = "keep-alive")) {
            Result.success()
        } else {
            startInline()
        }
    }

    /**
     * True while the strict app lock holds the database shut.
     *
     * Reported as success rather than retry: there is nothing to keep alive until the user
     * authenticates, and a retry would only burn WorkManager's backoff waiting for them.
     */
    private fun nothingToKeepAlive(): Boolean {
        val locked = databaseKeyProvider.isLocked
        if (locked) AppLogger.info(TAG, "app lock is holding the database; nothing to keep alive")
        return locked
    }

    private suspend fun startInline(): Result = runCatching {
        networkCoordinator.get().ensureStartedInline(NetworkLifecycleService.DEFAULT_LISTEN_PORT)
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { throwable ->
            // WorkManager cancels the coroutine when it stops the worker; that is
            // not a failure of the start and must not be swallowed.
            if (throwable is CancellationException) throw throwable
            AppLogger.warn(TAG, "in-process network start failed: $throwable")
            Result.retry()
        },
    )

    companion object {
        /** Unique name of the periodic work enqueued by `VMessengerApplication`. */
        const val UNIQUE_WORK_NAME = "network-keep-alive"

        private const val TAG = "Network"
        private const val REPEAT_INTERVAL_MINUTES = 15L

        fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<NetworkKeepAliveWorker>(REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
    }
}
