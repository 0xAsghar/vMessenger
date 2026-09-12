package ir.vmessenger.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.data.repository.ContactRepositoryImpl
import ir.vmessenger.data.repository.conflictsWithPinnedStaticKey
import ir.vmessenger.data.repository.findByIdentityHash
import ir.vmessenger.data.repository.findContactForInbound
import ir.vmessenger.data.repository.updateLearnedKeys
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.usecase.discovery.JoinNetworkUseCase
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import ir.vmessenger.network.messaging.RelayDirectory
import ir.vmessenger.network.messaging.RelayListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // one entry point per lifecycle phase plus the inbound hooks
class NetworkCoordinator @Inject constructor(
    private val joinNetworkUseCase: JoinNetworkUseCase,
    private val publishNetworkEndpointsUseCase: PublishNetworkEndpointsUseCase,
    private val messagingService: MessagingService,
    private val relayListener: RelayListener,
    private val incomingMessageCollector: IncomingMessageCollector,
    private val outboxDispatcher: OutboxDispatcher,
    private val contactRequestRetryWorker: ContactRequestRetryWorker,
    private val identityRepository: IdentityRepository,
    private val selfIdentityCache: SelfIdentityCache,
    private val contactDao: ContactDao,
    private val contactRepository: ContactRepositoryImpl,
    private val relayDirectory: RelayDirectory,
    private val embeddedDhtService: ir.vmessenger.network.dht.EmbeddedDhtService,
    private val peerRelayCoordinator: PeerRelayCoordinator,
    private val p2pConfigLoader: P2PConfigLoader,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler("Network"))

    @Volatile
    private var started = false

    @Volatile
    private var connectivityRegistered = false

    // When the device switches network (Wi-Fi <-> mobile data) or regains
    // connectivity, immediately flush queued messages instead of waiting out
    // their backoff. The relay listener reconnects on its own; this just makes
    // pending sends prompt.
    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLogger.info("Network", "connectivity available; flushing outbox")
            outboxDispatcher.retryNow()
        }
    }

    fun start(
        listenPort: Int,
        directHost: String? = null,
        directPort: Int? = null,
    ) {
        // onStartCommand re-fires on every app launch and START_STICKY restart;
        // a second full start would double TCP listeners and retry loops. A dev
        // restart (directHost set) still re-runs join/publish with the new config.
        if (started) {
            if (directHost != null) {
                AppLogger.info("Network", "coordinator already started; re-joining with dev config")
                rejoin(directHost, directPort)
            } else {
                AppLogger.info("Network", "coordinator already started; waking outbox")
                outboxDispatcher.wake()
            }
            return
        }
        started = true
        registerConnectivityCallback()
        scope.launch {
            p2pConfigLoader.loadIntoConfig()
            relayDirectory.activeRelay()
            AppLogger.info("Network", "coordinator start listenPort=$listenPort dev=${directHost != null}")
            runCatching { contactRepository.normalizeUserHashes() }
                .onFailure { AppLogger.warn("Network", "user hash normalization failed: ${it.message}") }
            configureInbound()
            incomingMessageCollector.start()
            outboxDispatcher.start()
            contactRequestRetryWorker.start()
            messagingService.startListening(listenPort)
            AppLogger.info("Network", "TCP listener started on $listenPort")
            if (ir.vmessenger.core.common.network.P2PConfig.dhtParticipationEnabled) {
                val dhtPort = listenPort + ir.vmessenger.network.dht.EmbeddedDhtService.PORT_OFFSET
                val host = directHost ?: "0.0.0.0"
                embeddedDhtService.start(dhtPort, host)
            }
            var joinSucceeded = false
            when (val join = joinNetworkUseCase()) {
                is AppResult.Success -> {
                    joinSucceeded = true
                    AppLogger.info("Network", "join network OK")
                }
                is AppResult.Error ->
                    AppLogger.error("Network", "join network failed: ${join.error.message}")
            }
            publishAndStartRelay(directHost = directHost, directPort = directPort)
            if (!joinSucceeded) {
                scope.launch { retryBootstrapAndPublish(directHost, directPort) }
            }
        }
    }

    /**
     * Tears the whole network stack down (secure wipe): no more connectivity
     * wake-ups, every coordinator job cancelled, the outbox and request-retry
     * loops stopped (so nothing re-dials after the sessions close), relay
     * control channel closed, the embedded DHT listener and the TCP listener
     * stopped and every live session closed. A later [start] brings everything
     * back up.
     */
    suspend fun stop() {
        started = false
        unregisterConnectivityCallback()
        scope.coroutineContext.cancelChildren()
        outboxDispatcher.stop()
        contactRequestRetryWorker.stop()
        relayListener.stop()
        incomingMessageCollector.stop()
        embeddedDhtService.stop()
        runCatching { messagingService.closeAll() }
            .onFailure { AppLogger.warn("Network", "closeAll failed: ${it.message}") }
        AppLogger.info("Network", "coordinator stopped")
    }

    private fun registerConnectivityCallback() {
        if (connectivityRegistered) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.registerDefaultNetworkCallback(connectivityCallback) }
            .onSuccess { connectivityRegistered = true }
            .onFailure { AppLogger.warn("Network", "connectivity callback register failed: ${it.message}") }
    }

    private fun unregisterConnectivityCallback() {
        if (!connectivityRegistered) return
        connectivityRegistered = false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.unregisterNetworkCallback(connectivityCallback) }
            .onFailure { AppLogger.warn("Network", "connectivity callback unregister failed: ${it.message}") }
    }

    private fun rejoin(directHost: String?, directPort: Int?) {
        scope.launch {
            when (val join = joinNetworkUseCase()) {
                is AppResult.Success -> AppLogger.info("Network", "re-join network OK")
                is AppResult.Error -> AppLogger.error("Network", "re-join failed: ${join.error.message}")
            }
            publishAndStartRelay(directHost = directHost, directPort = directPort)
        }
    }

    private suspend fun retryBootstrapAndPublish(
        directHost: String?,
        directPort: Int?,
    ) {
        var backoffMs = 10_000L
        while (true) {
            delay(backoffMs)
            when (joinNetworkUseCase()) {
                is AppResult.Success -> {
                    AppLogger.info("Network", "join network recovered after retry")
                    val selectedRelay = relayDirectory.activeRelay()
                    NetworkPathTracker.setActiveRelay(selectedRelay.url)
                    when (
                        val publish = publishNetworkEndpointsUseCase(
                            directHost = directHost,
                            directPort = directPort,
                            relayUrl = selectedRelay.url,
                        )
                    ) {
                        is AppResult.Success ->
                            AppLogger.info("Network", "publish endpoints OK after retry")
                        is AppResult.Error ->
                            AppLogger.error("Network", "publish endpoints failed after retry: ${publish.error.message}")
                    }
                    // Connectivity recovered — flush anything that piled up offline.
                    outboxDispatcher.retryNow()
                    return
                }
                is AppResult.Error -> {
                    backoffMs = (backoffMs * 2).coerceAtMost(120_000L)
                }
            }
        }
    }

    private suspend fun publishAndStartRelay(
        directHost: String?,
        directPort: Int?,
    ) {
        val self = awaitIdentityWithKey()
        // Resolve the active relay first so the endpoint we publish matches the
        // relay our listener will actually connect through (multi-relay support).
        val selectedRelay = relayDirectory.activeRelay()
        NetworkPathTracker.setActiveRelay(selectedRelay.url)
        AppLogger.info("Network", "active relay=${selectedRelay.url} source=${selectedRelay.source}")
        when (
            val publish = publishNetworkEndpointsUseCase(
                directHost = directHost,
                directPort = directPort,
                relayUrl = selectedRelay.url,
            )
        ) {
            is AppResult.Success ->
                AppLogger.info("Network", "publish endpoints OK")
            is AppResult.Error ->
                AppLogger.error("Network", "publish endpoints failed: ${publish.error.message}")
        }
        messagingService.startRelayListener(
            identityHash = self.identityHash,
            identityPub = self.ed25519PublicKey,
            ed25519PrivateKeyProvider = { selfIdentityCache.ed25519PrivateKey() },
        )
        AppLogger.info("Network", "relay listener starting")
        if (ir.vmessenger.core.common.network.P2PConfig.relayPeerModeEnabled) {
            peerRelayCoordinator.logStatus()
        }
        // Listener is up and endpoints are published — retry any queued messages now.
        outboxDispatcher.retryNow()
    }

    /** Waits until an identity exists *and* its key material is unwrappable (the cache serves it). */
    private suspend fun awaitIdentityWithKey(): PeerIdentity {
        var loggedWait = false
        while (true) {
            val self = selfIdentityCache.get()
            if (self != null) {
                if (loggedWait) {
                    AppLogger.info("Network", "identity and keys ready for publish/relay")
                }
                return self
            }
            if (!loggedWait) {
                AppLogger.info("Network", "waiting for identity and keys before publish/relay")
                loggedWait = true
            }
            if (identityRepository.getIdentity() == null) {
                identityRepository.observeIdentity().filterNotNull().first()
            } else {
                delay(KEY_POLL_MS)
            }
        }
    }

    private suspend fun configureInbound() {
        messagingService.configureInbound(
            selfProvider = { selfIdentityCache.get() },
            resolveInboundPeer = { identityPub, staticPub -> resolveInboundPeer(identityPub, staticPub) },
            contactIdResolver = { identityHash ->
                contactDao.findByIdentityHash(identityHash)?.id
                    ?: ContactRequestHandler.strangerContactId(identityHash)
            },
            peerKeyUpdater = { contactId, peer ->
                contactDao.updateLearnedKeys(
                    contactId = contactId,
                    identityHash = peer.identityHash,
                    ed25519Public = peer.ed25519PublicKey,
                    x25519StaticPublic = peer.x25519StaticPublicKey,
                )
                AppLogger.info("Contact", "learned peer keys for contact=$contactId")
                outboxDispatcher.wake()
            },
            // Initiator side of key pinning: the contact we dialled presented a
            // static key other than the pinned one. Mirror the responder path so
            // the user can review and accept the change (never adopted silently).
            peerKeyChangeRecorder = { contactId, newStaticKey ->
                contactDao.recordPendingKeyChange(contactId, newStaticKey, System.currentTimeMillis())
                AppLogger.warn("Contact", "key change pending contact=$contactId (outbound rejected)")
            },
        )
    }

    /**
     * Responder-side identity resolution for an inbound handshake. The signature
     * over the transcript is already verified by the time this runs, so a known
     * contact presenting a static key different from the pinned one is a real key
     * change: it is recorded as pending and the handshake is refused (`null`)
     * until the user accepts the new key. Blocked contacts are refused outright
     * so they cannot even open a session. Strangers are admitted so contact
     * requests can reach us; the inbound policy decides what they may send.
     */
    private suspend fun resolveInboundPeer(identityPub: ByteArray, staticPub: ByteArray): PeerIdentity? {
        val hash = UserHashEncoder.identityHashFromPublicKey(identityPub)
        val contact = contactDao.findContactForInbound(identityPub, hash)
        val admitted = when {
            contact == null -> {
                AppLogger.info("Contact", "inbound stranger hash=${IdentityHashMatcher.hashPrefixHex(hash)}")
                true
            }
            contact.blocked -> {
                AppLogger.warn("Contact", "blocked contact handshake rejected contact=${contact.id}")
                false
            }
            contact.conflictsWithPinnedStaticKey(staticPub) -> {
                contactDao.recordPendingKeyChange(contact.id, staticPub, System.currentTimeMillis())
                AppLogger.warn("Contact", "key change pending contact=${contact.id} (rejected)")
                false
            }
            else -> true
        }
        if (!admitted) return null
        return PeerIdentity(
            identityHash = hash,
            ed25519PublicKey = identityPub,
            x25519StaticPublicKey = staticPub,
        )
    }

    companion object {
        private const val KEY_POLL_MS = 100L
    }
}
