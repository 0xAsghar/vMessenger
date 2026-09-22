package ir.vmessenger.app.work

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.app.network.NetworkKeepAliveWorker
import ir.vmessenger.data.wipe.BackgroundWorkControl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cancels the periodic work this module enqueues, by the same unique names it enqueued them under.
 *
 * Deliberately not `cancelAllWork()`: that would also drop work belonging to libraries, and naming
 * them keeps this honest about what the app actually schedules. The application re-enqueues both on
 * its next start, which is right — after a wipe the install is new, and a new install wants them.
 */
@Singleton
class AndroidBackgroundWorkControl @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackgroundWorkControl {
    override fun cancelAll() {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(NetworkKeepAliveWorker.UNIQUE_WORK_NAME)
        manager.cancelUniqueWork(ExpiryPurgeWorker.UNIQUE_WORK_NAME)
    }
}
