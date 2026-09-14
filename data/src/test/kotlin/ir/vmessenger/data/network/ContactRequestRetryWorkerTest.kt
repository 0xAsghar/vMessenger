package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeIdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ContactRequestRetryWorkerTest {
    private val peerA = InboundFixtures.peer(0x0A)
    private val t0 = 1_700_000_000_000L

    private lateinit var contactDao: FakeContactDao
    private lateinit var messaging: FakeMessagingPort
    private lateinit var service: ContactRequestService
    private lateinit var worker: ContactRequestRetryWorker
    private lateinit var store: FakeContactRequestRetryStore
    private lateinit var identityRepository: FakeIdentityRepository
    private lateinit var identityCache: SelfIdentityCache

    @Before
    fun setUp() {
        val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        identityRepository = FakeIdentityRepository(cryptoEngine)
        InboundFixtures.installIdentity(identityRepository, 0x01)
        identityCache = SelfIdentityCache(identityRepository, cryptoEngine)
        store = FakeContactRequestRetryStore()
        contactDao = FakeContactDao()
        messaging = FakeMessagingPort()
        worker = newWorker()
    }

    /** A worker over a fresh budget but the same disk: what a process restart gives. */
    private fun newWorker(): ContactRequestRetryWorker {
        val budget = ContactRequestRetryBudget(store)
        service = ContactRequestService(identityRepository, identityCache, messaging, budget)
        return ContactRequestRetryWorker(
            contactDao = contactDao,
            contactRepository = FakeContactRepository(contactDao),
            contactRequestService = service,
            selfIdentityCache = identityCache,
            budget = budget,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun requestsSent() = messaging.sent.count { it.second.hasContactRequest() }

    @Test
    fun stopsAfterCap() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)
        val cap = ContactRequestRetryWorker.MAX_ATTEMPTS_PER_CONTACT

        // Every delivered request is repeated after the 5-minute heartbeat ...
        var lastSend = t0
        repeat(cap) { attempt ->
            lastSend = t0 + attempt * ContactRequestRetryWorker.DELIVERED_REPEAT_MS
            worker.runPass(lastSend)
        }
        assertEquals(cap, requestsSent())

        // ... until the budget is spent: then only once a day.
        worker.runPass(lastSend + ContactRequestRetryWorker.DELIVERED_REPEAT_MS)
        assertEquals(cap, requestsSent())
        worker.runPass(lastSend + ContactRequestRetryWorker.CAPPED_REPEAT_MS - 1)
        assertEquals(cap, requestsSent())
        worker.runPass(lastSend + ContactRequestRetryWorker.CAPPED_REPEAT_MS)
        assertEquals(cap + 1, requestsSent())
    }

    @Test
    fun userResendResetsBudget() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)
        var now = t0
        repeat(ContactRequestRetryWorker.MAX_ATTEMPTS_PER_CONTACT) {
            worker.runPass(now)
            now += ContactRequestRetryWorker.DELIVERED_REPEAT_MS
        }
        val capped = requestsSent()
        worker.runPass(now)
        assertEquals(capped, requestsSent())

        // The user's own re-send starts the automatic budget over.
        val contact = requireNotNull(FakeContactRepository(contactDao).getContact("a"))
        service.sendRequest(contact)
        worker.runPass(now)
        assertEquals(capped + 2, requestsSent())
    }

    @Test
    fun qrAddedOnlyWithinWindow() = runTest {
        val window = ContactRequestRetryWorker.QR_WINDOW_MS
        contactDao.contacts += InboundFixtures.contact("fresh", peerA)
            .copy(createdAtUnixMs = t0 - window + 60_000L)
        contactDao.contacts += InboundFixtures.contact("stale", InboundFixtures.peer(0x0B))
            .copy(createdAtUnixMs = t0 - window - 60_000L)
        contactDao.contacts += InboundFixtures.contact("answered", InboundFixtures.peer(0x0C))
            .copy(createdAtUnixMs = t0, lastSeenUnixMs = t0)

        worker.runPass(t0)

        assertEquals(listOf("fresh"), messaging.sent.map { it.first })
    }

    @Test
    fun failedSendsBackOffThenRetry() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)
        messaging.sendError = AppError.Network("offline")

        worker.runPass(t0)
        worker.runPass(t0 + 1_000L)
        messaging.sendError = null
        worker.runPass(t0 + 1_000L)
        assertEquals(0, requestsSent())

        worker.runPass(t0 + 15 * 60_000L)
        assertEquals(1, requestsSent())
    }

    /**
     * The cap only means anything if it outlives the process. Held in memory, it
     * reset on every launch, so a contact that stopped answering months ago was
     * dialled hard all over again each time the app started.
     */
    @Test
    fun budgetSurvivesARestart() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)
        val cap = ContactRequestRetryWorker.MAX_ATTEMPTS_PER_CONTACT
        var now = t0
        repeat(cap) {
            worker.runPass(now)
            now += ContactRequestRetryWorker.DELIVERED_REPEAT_MS
        }
        assertEquals(cap, requestsSent())

        worker = newWorker()

        worker.runPass(now)
        assertEquals("the restored budget is still spent", cap, requestsSent())
        worker.runPass(now + ContactRequestRetryWorker.CAPPED_REPEAT_MS)
        assertEquals(cap + 1, requestsSent())
    }

    @Test
    fun theUsersOwnResendClearsTheContactFromDisk() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peerA, status = ContactRelationshipStatus.PENDING_OUT)
        worker.runPass(t0)
        assertEquals(setOf("a"), store.saved.keys)

        service.sendRequest(requireNotNull(FakeContactRepository(contactDao).getContact("a")))

        assertEquals(emptySet<String>(), store.saved.keys)
    }
}

/** Stands in for the DataStore: one map that outlives the budget reading it. */
private class FakeContactRequestRetryStore : ContactRequestRetryStore {
    var saved: Map<String, ContactRequestRetryBudget.State> = emptyMap()
        private set

    override suspend fun load(): Map<String, ContactRequestRetryBudget.State> = saved

    override suspend fun save(states: Map<String, ContactRequestRetryBudget.State>) {
        saved = states
    }
}
