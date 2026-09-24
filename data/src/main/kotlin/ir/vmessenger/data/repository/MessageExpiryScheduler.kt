package ir.vmessenger.data.repository

import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.MessageDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Erases each self-destructing message at its deadline, for as long as the app is running.
 *
 * The periodic sweep runs every 15 minutes — as often as WorkManager allows — so on its own a
 * message timed for 14:30 could still be on screen at 14:44. This waits for the soonest deadline on
 * the device and purges then; the purge changes what the soonest deadline is, so it re-arms itself,
 * and a newly sent or received timed message that is due sooner cuts the wait short.
 *
 * The wait is taken a minute at a time and re-measured against the wall clock each time round,
 * because a coroutine's delay does not count time the device spends asleep: a single week-long
 * delay would come due a week of *awake* time later.
 */
@Singleton
class MessageExpiryScheduler @Inject constructor(
    private val messageDao: MessageDao,
    private val writer: ConversationWriter,
) {
    /** Injectable clock so the wait is testable. */
    var clock: () -> Long = System::currentTimeMillis

    /** Runs until cancelled; the network coordinator keeps it running for as long as it is up. */
    suspend fun run() {
        messageDao.observeNextExpiry()
            .distinctUntilChanged()
            .collectLatest { next -> if (next != null) purgeAt(next) }
    }

    private suspend fun purgeAt(deadline: Long) {
        var remaining = deadline - clock()
        while (remaining > 0) {
            delay(remaining.coerceAtMost(MAX_WAIT_MS))
            remaining = deadline - clock()
        }
        runCatching { writer.purgeExpired(clock()) }.onFailure { failure ->
            // Being replaced by a sooner deadline is not a failure, and must reach collectLatest.
            if (failure is CancellationException) throw failure
            // The periodic sweep is still there; a failed purge here only means it gets the row.
            AppLogger.warn(TAG, "expiry purge failed: $failure")
        }
    }

    private companion object {
        const val TAG = "Messaging"
        const val MAX_WAIT_MS = 60_000L
    }
}
