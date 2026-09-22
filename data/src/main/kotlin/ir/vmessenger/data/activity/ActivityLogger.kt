package ir.vmessenger.data.activity

import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ActivityLogDao
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.database.entity.ActivityLogEntity
import ir.vmessenger.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the device's own activity log.
 *
 * [record] does not suspend and never throws. It is called from lock transitions, network
 * callbacks, permission results and the wipe — places where a log write must not be able to fail
 * the thing it is describing. A failed write is logged to the ordinary app log and dropped: an
 * audit trail that can take the app down with it is worse than one with a gap.
 *
 * What may be recorded is decided by [ActivityLogEntity], not here. The short version: what the
 * user did to the app, never who they communicated with.
 */
@Singleton
class ActivityLogger @Inject constructor(
    private val dao: ActivityLogDao,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler(TAG))

    /** Fire-and-forget. See the class note on why this cannot be allowed to fail a caller. */
    fun record(kind: ActivityKind, detail: String? = null) {
        scope.launch {
            runCatching { write(kind, detail) }
                .onFailure { AppLogger.warn(TAG, "could not record $kind: ${it.message}") }
        }
    }

    fun observe(limit: Int = PAGE_SIZE): Flow<List<ActivityLogEntity>> = dao.observe(limit)

    suspend fun recent(limit: Int = PAGE_SIZE): List<ActivityLogEntity> = dao.recent(limit)

    /** Erased outright rather than purged: on a wipe there is no account left to keep a log for. */
    suspend fun clear() {
        runCatching { dao.clear() }
            .onFailure { AppLogger.warn(TAG, "could not clear the activity log: ${it.message}") }
    }

    private suspend fun write(kind: ActivityKind, detail: String?) {
        val now = System.currentTimeMillis()
        dao.insert(
            ActivityLogEntity(
                kind = kind,
                // Bounded so a long value cannot turn one entry into a place to store things.
                detail = detail?.take(MAX_DETAIL_CHARS)?.takeIf { it.isNotBlank() },
                atUnixMs = now,
            ),
        )
        dao.purgeOlderThan(now - RETENTION_MS)
    }

    private companion object {
        const val TAG = "Activity"
        const val MAX_DETAIL_CHARS = 120
        const val PAGE_SIZE = 500

        /** Ninety days. A log to check after the fact, not a permanent record of a life. */
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(90)
    }
}
