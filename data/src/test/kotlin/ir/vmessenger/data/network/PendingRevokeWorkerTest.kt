package ir.vmessenger.data.network

import ir.vmessenger.core.database.entity.PendingRevokeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a deleted contact that they were deleted, when they were not there to hear it.
 *
 * The queue exists because the old code sent this once, bounded at ten seconds, and never again —
 * and people delete contacts they are not currently talking to, so the peer was usually offline.
 */
class PendingRevokeWorkerTest {
    private val dao = FakePendingRevokeDao()

    @Test
    fun `a delivered revoke leaves the queue`() = runTest {
        dao.upsert(pending(createdAt = 0, nextAttempt = 0))

        worker().runPass(now = 1_000)

        // Delivered, so it is done: the row exists only until the peer has been told once.
        assertTrue(dao.queued.isEmpty())
    }

    @Test
    fun `a revoke older than the give-up window is purged`() = runTest {
        dao.upsert(pending(createdAt = 0, nextAttempt = 0))

        // Eight days later: a peer who has not appeared in a week is not going to, and holding
        // their keys past that works against the deletion that queued this.
        worker().runPass(now = 8L * 24 * 60 * 60 * 1000)

        assertTrue(dao.queued.isEmpty())
    }

    @Test
    fun `only rows whose backoff has elapsed are attempted`() = runTest {
        dao.upsert(pending(createdAt = 0, nextAttempt = 10_000))

        assertTrue(dao.due(now = 5_000).isEmpty())
        assertEquals(1, dao.due(now = 10_000).size)
    }

    private fun worker(): PendingRevokeWorker {
        val harness = InboundHarness()
        return PendingRevokeWorker(
            pendingRevokeDao = dao,
            contactRequestService = ContactRequestService(
                harness.identityRepository,
                harness.selfIdentityCache,
                harness.messaging,
                ContactRequestRetryBudget(ContactRequestRetryStore.Transient),
                Dispatchers.Unconfined,
            ),
            selfIdentityCache = harness.selfIdentityCache,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    private fun pending(createdAt: Long, nextAttempt: Long) = PendingRevokeEntity(
        identityHash = ByteArray(32) { 0x0A },
        ed25519Public = ByteArray(32) { 0x0B },
        x25519StaticPublic = ByteArray(32) { 0x0C },
        requestId = "cr-abc",
        createdAtUnixMs = createdAt,
        nextAttemptUnixMs = nextAttempt,
    )
}
