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

    /**
     * Two peers deleted while offline, both back by the next pass. Sent under one shared id, the
     * second revoke reused the session dialled for the first and never reached its own peer.
     */
    @Test
    fun `each revoke goes out under its own peer's slot`() = runTest {
        val harness = InboundHarness()
        dao.upsert(pending(createdAt = 0, nextAttempt = 0))
        dao.upsert(pending(createdAt = 0, nextAttempt = 0, peerByte = 0x0D))

        worker(harness).runPass(now = 1_000)

        val slots = harness.messaging.sent.map { it.first }
        assertEquals(2, slots.size)
        assertEquals(2, slots.toSet().size)
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

    private fun worker(harness: InboundHarness = InboundHarness()): PendingRevokeWorker =
        PendingRevokeWorker(
            pendingRevokeDao = dao,
            contactRequestService = ContactRequestService(
                harness.identityRepository,
                harness.selfIdentityCache,
                harness.messaging,
                ContactRequestRetryBudget(ContactRequestRetryStore.Transient),
                Dispatchers.Unconfined,
            ),
            selfIdentityCache = harness.selfIdentityCache,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun pending(createdAt: Long, nextAttempt: Long, peerByte: Byte = 0x0A) = PendingRevokeEntity(
        identityHash = ByteArray(32) { peerByte },
        ed25519Public = ByteArray(32) { 0x0B },
        x25519StaticPublic = ByteArray(32) { 0x0C },
        requestId = "cr-abc",
        createdAtUnixMs = createdAt,
        nextAttemptUnixMs = nextAttempt,
    )
}
