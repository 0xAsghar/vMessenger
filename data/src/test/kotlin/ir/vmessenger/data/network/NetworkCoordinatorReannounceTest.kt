package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.data.repository.FakeIdentityRepository
import ir.vmessenger.domain.model.DiscoveryStatus
import ir.vmessenger.domain.repository.DiscoveryRepository
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The endpoint record expires after the discovery TTL, so the app has to
 * re-announce while it runs; publishing once at start left peers unable to
 * resolve this device after twenty minutes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkCoordinatorReannounceTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var discovery: RecordingDiscoveryRepository
    private lateinit var identityRepository: FakeIdentityRepository
    private lateinit var announcer: EndpointAnnouncer

    @Before
    fun setUp() {
        val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        discovery = RecordingDiscoveryRepository()
        identityRepository = FakeIdentityRepository(cryptoEngine)
        announcer = EndpointAnnouncer(
            publishNetworkEndpointsUseCase = PublishNetworkEndpointsUseCase(discovery),
            selfIdentityCache = SelfIdentityCache(identityRepository, cryptoEngine),
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        announcer.stop()
    }

    /**
     * Every test stops the loop before it ends: a pending periodic task would
     * otherwise keep the test scheduler busy forever after the body returns.
     */
    private fun announcerTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            announcer.stop()
        }
    }

    private fun arm() = announcer.start(directHost = null, directPort = null, relayUrl = RELAY_URL)

    @Test
    fun republishesEveryHalfTtl() = announcerTest {
        InboundFixtures.installIdentity(identityRepository, 0x01)
        arm()

        // The caller published already, so nothing happens for a full period ...
        advanceTimeBy(EndpointAnnouncer.PERIOD_MS - 1)
        runCurrent()
        assertEquals(0, discovery.attempts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, discovery.attempts)
        assertEquals(listOf(Endpoint(TransportIds.RELAY, RELAY_URL)), discovery.published.last())

        // ... and then once every period, for as long as the app runs.
        advanceTimeBy(EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        assertEquals(2, discovery.attempts)
        advanceTimeBy(EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        assertEquals(3, discovery.attempts)
    }

    @Test
    fun connectivityReturnReannouncesImmediately() = announcerTest {
        InboundFixtures.installIdentity(identityRepository, 0x01)
        arm()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(0, discovery.attempts)

        // What NetworkCoordinator's connectivity callback does.
        announcer.announceNow()
        runCurrent()
        assertEquals(1, discovery.attempts)

        // The period restarts from the immediate announce.
        advanceTimeBy(EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        assertEquals(2, discovery.attempts)
    }

    @Test
    fun stopCancelsTheLoop() = announcerTest {
        InboundFixtures.installIdentity(identityRepository, 0x01)
        arm()
        advanceTimeBy(EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        assertEquals(1, discovery.attempts)

        announcer.stop()
        advanceTimeBy(4 * EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        announcer.announceNow()
        runCurrent()
        assertEquals(1, discovery.attempts)
    }

    @Test
    fun nothingIsAnnouncedBeforeAnIdentityExists() = announcerTest {
        arm()

        advanceTimeBy(3 * EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        announcer.announceNow()
        runCurrent()
        assertEquals(0, discovery.attempts)

        // Once the identity is there the loop resumes on its own.
        InboundFixtures.installIdentity(identityRepository, 0x01)
        advanceTimeBy(EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        assertEquals(1, discovery.attempts)
    }

    @Test
    fun failedAnnounceBacksOffInsteadOfTightLooping() = announcerTest {
        InboundFixtures.installIdentity(identityRepository, 0x01)
        discovery.error = AppError.Network("offline")
        arm()

        advanceTimeBy(EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        assertEquals(1, discovery.attempts)

        // No retry storm: the next attempt is a backoff away, not immediate.
        advanceTimeBy(EndpointAnnouncer.FAILURE_BACKOFF_MS - 1)
        runCurrent()
        assertEquals(1, discovery.attempts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, discovery.attempts)

        // Second failure doubles the backoff.
        advanceTimeBy(2 * EndpointAnnouncer.FAILURE_BACKOFF_MS - 1)
        runCurrent()
        assertEquals(2, discovery.attempts)

        discovery.error = null
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, discovery.attempts)
        assertEquals(1, discovery.published.size)

        // Back to the normal period after a success.
        advanceTimeBy(EndpointAnnouncer.PERIOD_MS)
        runCurrent()
        assertEquals(2, discovery.published.size)
    }

    private companion object {
        const val RELAY_URL = "wss://relay.example/ws"
    }
}

/** Counts every publish attempt; [error] makes them fail without recording endpoints. */
private class RecordingDiscoveryRepository : DiscoveryRepository {
    val published = mutableListOf<List<Endpoint>>()
    var attempts = 0
    var error: AppError? = null

    override fun observeStatus(): Flow<DiscoveryStatus> = emptyFlow()

    override suspend fun joinNetwork(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun publishEndpoint(endpoint: Endpoint): AppResult<Unit> =
        publishEndpoints(listOf(endpoint))

    override suspend fun publishEndpoints(endpoints: List<Endpoint>): AppResult<Unit> {
        attempts += 1
        error?.let { return AppResult.Error(it) }
        published += endpoints
        return AppResult.Success(Unit)
    }

    override suspend fun getPublishedEndpoint(): String? = published.lastOrNull()?.firstOrNull()?.address
}
