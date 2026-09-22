package ir.vmessenger.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.DatabaseKeyProvider
import ir.vmessenger.domain.usecase.chat.PurgeExpiredMessagesUseCase
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Sweeps self-destructing messages whose absolute deadline has passed. Runs every 15 minutes (the
 * shortest period WorkManager allows); it is the backstop for a device left unopened, so the worst
 * case is that an expired message lingers on disk until the next sweep, never that it is shown.
 *
 * A no-op while the strict app lock holds the database shut — reported as success, since there is
 * nothing to purge until the user authenticates and a retry would only burn WorkManager's backoff.
 */
@HiltWorker
class ExpiryPurgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    // Provider: resolving the use case builds the database, which throws while the strict app lock
    // holds it shut; a worker constructed then would fail before doWork() could decide to skip.
    private val purgeExpiredMessages: Provider<PurgeExpiredMessagesUseCase>,
    private val databaseKeyProvider: DatabaseKeyProvider,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (databaseKeyProvider.isLocked) return Result.success()
        return runCatching { purgeExpiredMessages.get().invoke() }.fold(
            onSuccess = { Result.success() },
            onFailure = { throwable ->
                // WorkManager cancels the coroutine when it stops the worker; that is not a purge
                // failure and must propagate rather than be reported as a retry.
                if (throwable is CancellationException) throw throwable
                AppLogger.warn(TAG, "expiry purge failed: $throwable")
                Result.retry()
            },
        )
    }

    companion object {
        /** Unique name of the periodic work enqueued by `VMessengerApplication`. */
        const val UNIQUE_WORK_NAME = "message-expiry-purge"

        private const val TAG = "Messaging"
        private const val REPEAT_INTERVAL_MINUTES = 15L

        fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ExpiryPurgeWorker>(REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES).build()
    }
}
