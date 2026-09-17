package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.PendingRevokeDao
import ir.vmessenger.core.database.entity.PendingRevokeEntity
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps telling a deleted contact that they were deleted, until they hear it.
 *
 * The revoke used to be sent once, bounded at ten seconds and never retried. People delete contacts
 * they are not currently talking to, so the usual case was a peer who was offline and never found
 * out: they kept us APPROVED indefinitely, their messages were dropped by the inbound policy with
 * only a log line, and their own outbox sat on a single tick forever. Nobody on either side was
 * told anything.
 *
 * Mirrors the contact-request retry worker deliberately — same poll, same backoff shape — because
 * it is the same problem in the opposite direction. It gives up after [MAX_AGE_MS]: a peer who has
 * not appeared in a week is not going to, and holding their keys past that point works against the
 * deletion that started this.
 */
@Singleton
class PendingRevokeWorker @Inject constructor(
    private val pendingRevokeDao: PendingRevokeDao,
    private val contactRequestService: ContactRequestService,
    private val selfIdentityCache: SelfIdentityCache,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler(TAG))

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { runPass(System.currentTimeMillis()) }
                    .onFailure { AppLogger.warn(TAG, "revoke pass failed: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        started = false
        scope.coroutineContext.cancelChildren()
    }

    /** One pass as of [now]; public so a test can drive it without the loop. */
    suspend fun runPass(now: Long) {
        if (selfIdentityCache.get() == null) return
        pendingRevokeDao.purgeOlderThan(now - MAX_AGE_MS)
        for (pending in pendingRevokeDao.due(now)) {
            if (deliver(pending)) {
                pendingRevokeDao.delete(pending.identityHash)
                AppLogger.info(TAG, "revoke finally delivered after ${pending.attemptCount} attempt(s)")
            } else {
                pendingRevokeDao.update(pending.rescheduled(now))
            }
        }
    }

    private suspend fun deliver(pending: PendingRevokeEntity): Boolean {
        val peer = PeerIdentity(
            identityHash = pending.identityHash,
            ed25519PublicKey = pending.ed25519Public,
            x25519StaticPublicKey = pending.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
        )
        val result = runCatching {
            contactRequestService.sendResponse(
                // One slot per peer. The contact row is gone by now, which is the point of this
                // table, so there is no real contact id — but a shared placeholder was worse than
                // none: the messaging layer reuses an open session by this id without looking at
                // who is on the other end, so the second revoke of a pass went down the session
                // opened for the first, reached the wrong peer, and left the queue as delivered.
                contactId = SLOT_PREFIX + IdentityHashMatcher.routingKeyHex(pending.identityHash),
                peer = peer,
                requestId = pending.requestId,
                type = ContactResponseType.CONTACT_RESPONSE_REVOKE,
            )
        }.getOrNull()
        return result is AppResult.Success
    }

    private fun PendingRevokeEntity.rescheduled(now: Long): PendingRevokeEntity {
        val attempt = attemptCount + 1
        val backoff = (BASE_BACKOFF_MS shl minOf(attempt, MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
        return copy(attemptCount = attempt, nextAttemptUnixMs = now + backoff)
    }

    private companion object {
        const val TAG = "Contact"
        const val SLOT_PREFIX = "revoked:"
        const val POLL_INTERVAL_MS = 30_000L
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 15 * 60_000L
        const val MAX_SHIFT = 5
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val X25519_KEY_SIZE = 32
    }
}
