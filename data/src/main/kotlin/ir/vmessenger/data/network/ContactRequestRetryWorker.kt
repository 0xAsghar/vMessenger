package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.ContactRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-contact retry bookkeeping shared by the retry worker (which spends the
 * budget) and the request service (which resets it when the user re-sends).
 *
 * The budget outlives the process: it is read once on first use and written back
 * on every change. Held in memory only, it reset on every launch, which made
 * [ContactRequestRetryWorker.MAX_ATTEMPTS_PER_CONTACT] a cap on nothing — a
 * contact that has been unreachable for months was dialled hard from scratch
 * each time the app started.
 */
@Singleton
class ContactRequestRetryBudget @Inject constructor(
    private val store: ContactRequestRetryStore,
) {
    /** [attempts] counts every automatic send; [failures] only the consecutive failed ones (backoff). */
    data class State(val attempts: Int, val failures: Int, val nextAttemptUnixMs: Long)

    private val mutex = Mutex()

    /** Null until the first read pulls the persisted budget in. */
    private var states: MutableMap<String, State>? = null

    suspend fun state(contactId: String): State? = mutex.withLock { loaded()[contactId] }

    suspend fun record(contactId: String, state: State) = mutex.withLock {
        val current = loaded()
        current[contactId] = state
        store.save(current.toMap())
    }

    /** The user re-sent by hand: start the automatic budget over. */
    suspend fun reset(contactId: String) = mutex.withLock {
        val current = loaded()
        if (current.remove(contactId) != null) store.save(current.toMap())
    }

    suspend fun retainOnly(contactIds: Set<String>) = mutex.withLock {
        val current = loaded()
        if (current.keys.retainAll(contactIds)) store.save(current.toMap())
    }

    private suspend fun loaded(): MutableMap<String, State> =
        states ?: store.load().toMutableMap().also { states = it }
}

/**
 * Re-sends contact requests until the peer answers.
 *
 * Two cases both need a durable outbound request:
 *  - hash-added contacts (PENDING_OUT): usable only after the peer approves;
 *  - QR-added contacts (APPROVED locally): the peer still has to learn about us,
 *    so we owe them a request too until they respond — but only for
 *    [QR_WINDOW_MS] after the add; after that the peer is simply gone.
 *
 * A contact is considered "answered" once we have received anything from it
 * (lastSeenUnixMs set), so retries stop as soon as the peer responds. Every
 * contact gets [MAX_ATTEMPTS_PER_CONTACT] automatic sends; past the cap the
 * worker only knocks once a day ([CAPPED_REPEAT_MS]) so an abandoned request
 * cannot keep dialing forever. A manual re-send by the user resets the budget.
 * Request ids are deterministic per (requester, target) pair, so the receiver
 * dedupes repeated deliveries.
 */
@Singleton
class ContactRequestRetryWorker @Inject constructor(
    private val contactDao: ContactDao,
    private val contactRepository: ContactRepository,
    private val contactRequestService: ContactRequestService,
    private val selfIdentityCache: SelfIdentityCache,
    private val budget: ContactRequestRetryBudget,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { runPass(System.currentTimeMillis()) }
                    .onFailure { AppLogger.warn("Contact", "request retry pass failed: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the retry loop (coordinator stop); [start] resumes it. */
    fun stop() {
        started = false
        scope.coroutineContext.cancelChildren()
    }

    /** One retry pass as of [now]; public for tests, the loop calls it every [POLL_INTERVAL_MS]. */
    suspend fun runPass(now: Long) {
        if (selfIdentityCache.get() == null) return
        val owed = contactDao.getAll().filter { it.owesRequest(now) }
        val due = owed.filter { entity ->
            val state = budget.state(entity.id)
            state == null || now >= state.nextAttemptUnixMs
        }
        for (entity in due) {
            contactRepository.getContact(entity.id)?.let { contact ->
                sendWithBackoff(entity.id, contact, now)
            }
        }
        budget.retainOnly(owed.map { it.id }.toSet())
    }

    /**
     * PENDING_OUT: hash-added, waiting for approval. APPROVED + never heard
     * from: QR-added (or an unacknowledged add) — the peer still needs our
     * request, but only within the QR window. Once they respond,
     * lastSeenUnixMs is set and we stop.
     */
    private fun ContactEntity.owesRequest(now: Long): Boolean =
        !blocked && when (relationshipStatus) {
            ContactRelationshipStatus.PENDING_OUT -> true
            ContactRelationshipStatus.APPROVED -> lastSeenUnixMs == null && now - createdAtUnixMs < QR_WINDOW_MS
            else -> false
        }

    private suspend fun sendWithBackoff(contactId: String, contact: Contact, now: Long) {
        val previous = budget.state(contactId)
        val attempts = (previous?.attempts ?: 0) + 1
        val capped = attempts >= MAX_ATTEMPTS_PER_CONTACT
        when (val result = contactRequestService.deliverRequest(contact)) {
            is AppResult.Success -> {
                // Delivered; keep a slow heartbeat until the peer answers so a
                // lost response still heals (receiver auto-accepts duplicates).
                val repeat = if (capped) CAPPED_REPEAT_MS else DELIVERED_REPEAT_MS
                budget.record(contactId, ContactRequestRetryBudget.State(attempts, 0, now + repeat))
                AppLogger.info("Contact", "re-sent contact request to ${contact.userHash} (attempt $attempts)")
            }
            is AppResult.Error -> {
                val failures = (previous?.failures ?: 0) + 1
                val backoff = if (capped) {
                    CAPPED_REPEAT_MS
                } else {
                    (BASE_BACKOFF_MS shl minOf(failures, MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
                }
                budget.record(contactId, ContactRequestRetryBudget.State(attempts, failures, now + backoff))
                AppLogger.info(
                    "Contact",
                    "contact request retry $contactId failed (${result.error.message}), next in ${backoff}ms",
                )
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS_PER_CONTACT = 48
        const val QR_WINDOW_MS = 7L * 24 * 60 * 60_000L
        const val CAPPED_REPEAT_MS = 24L * 60 * 60_000L
        const val DELIVERED_REPEAT_MS = 5 * 60_000L
        private const val POLL_INTERVAL_MS = 30_000L
        private const val BASE_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 15 * 60_000L
        private const val MAX_SHIFT = 5
    }
}
