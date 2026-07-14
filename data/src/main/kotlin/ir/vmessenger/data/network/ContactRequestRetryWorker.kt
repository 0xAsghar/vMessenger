package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.IdentityRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-sends contact requests until the peer answers.
 *
 * Two cases both need a durable outbound request:
 *  - hash-added contacts (PENDING_OUT): usable only after the peer approves;
 *  - QR-added contacts (APPROVED locally): the peer still has to learn about us,
 *    so we owe them a request too until they respond.
 *
 * A contact is considered "answered" once we have received anything from it
 * (lastSeenUnixMs set), so retries stop as soon as the peer responds. The
 * initial send is best-effort (the peer may be offline or, on mobile, briefly
 * off the relay), so without retries the request is silently lost and the
 * relationship never completes. Request ids are deterministic per
 * (requester, target) pair, so the receiver dedupes repeated deliveries.
 */
@Singleton
class ContactRequestRetryWorker @Inject constructor(
    private val contactDao: ContactDao,
    private val contactRepository: ContactRepository,
    private val contactRequestService: ContactRequestService,
    private val identityRepository: IdentityRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val attempts = ConcurrentHashMap<String, RetryState>()

    @Volatile
    private var started = false

    private data class RetryState(val count: Int, val nextAttemptUnixMs: Long)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { retryPendingRequests() }
                    .onFailure { AppLogger.warn("Contact", "request retry pass failed: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun retryPendingRequests() {
        if (identityRepository.getIdentity() == null) return
        val now = System.currentTimeMillis()
        // PENDING_OUT: hash-added, waiting for approval.
        // APPROVED + never heard from: QR-added (or an unacknowledged add) — the
        // peer still needs our request. Once they respond, lastSeenUnixMs is set
        // and we stop.
        val owed = contactDao.getAll().filter { entity ->
            !entity.blocked && when (entity.relationshipStatus) {
                ContactRelationshipStatus.PENDING_OUT -> true
                ContactRelationshipStatus.APPROVED -> entity.lastSeenUnixMs == null
                else -> false
            }
        }
        val due = owed.filter { entity ->
            val state = attempts[entity.id]
            state == null || now >= state.nextAttemptUnixMs
        }
        for (entity in due) {
            contactRepository.getContact(entity.id)?.let { contact ->
                sendWithBackoff(entity.id, contact, now)
            }
        }
        attempts.keys.retainAll(owed.map { it.id }.toSet())
    }

    private suspend fun sendWithBackoff(
        contactId: String,
        contact: ir.vmessenger.domain.model.Contact,
        now: Long,
    ) {
        when (val result = contactRequestService.sendRequest(contact)) {
            is AppResult.Success -> {
                // Delivered; keep a slow heartbeat until the peer answers so a
                // lost response still heals (receiver auto-accepts duplicates).
                attempts[contactId] = RetryState(0, now + DELIVERED_REPEAT_MS)
                AppLogger.info("Contact", "re-sent contact request to ${contact.userHash}")
            }
            is AppResult.Error -> {
                val count = (attempts[contactId]?.count ?: 0) + 1
                val backoff = (BASE_BACKOFF_MS shl minOf(count, MAX_SHIFT))
                    .coerceAtMost(MAX_BACKOFF_MS)
                attempts[contactId] = RetryState(count, now + backoff)
                AppLogger.info(
                    "Contact",
                    "contact request retry $contactId failed (${result.error.message}), next in ${backoff}ms",
                )
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
        private const val BASE_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 15 * 60_000L
        private const val DELIVERED_REPEAT_MS = 5 * 60_000L
        private const val MAX_SHIFT = 5
    }
}
