package ir.vmessenger.data.activity

import ir.vmessenger.core.database.dao.ActivityLogDao
import ir.vmessenger.core.database.entity.ActivityLogEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class FakeActivityLogDao : ActivityLogDao {
    val rows = mutableListOf<ActivityLogEntity>()

    override suspend fun insert(entity: ActivityLogEntity) {
        rows += entity
    }

    override fun observe(limit: Int): Flow<List<ActivityLogEntity>> =
        flowOf(rows.sortedByDescending { it.atUnixMs }.take(limit))

    override suspend fun recent(limit: Int): List<ActivityLogEntity> =
        rows.sortedByDescending { it.atUnixMs }.take(limit)

    override suspend fun purgeOlderThan(cutoffUnixMs: Long) {
        rows.removeAll { it.atUnixMs < cutoffUnixMs }
    }

    override suspend fun clear() = rows.clear()
}

/**
 * A logger over an in-memory DAO, on an unconfined dispatcher so its fire-and-forget writes have
 * landed by the time the test looks. Pass [dao] when the test asserts on what was recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun testActivityLogger(dao: ActivityLogDao = FakeActivityLogDao()): ActivityLogger =
    ActivityLogger(dao, UnconfinedTestDispatcher())
