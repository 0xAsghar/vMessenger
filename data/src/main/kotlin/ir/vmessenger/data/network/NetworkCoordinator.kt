package ir.vmessenger.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.data.repository.findByIdentityHash
import ir.vmessenger.data.repository.findContactForInbound
import ir.vmessenger.data.repository.updateLearnedKeys
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.usecase.discovery.JoinNetworkUseCase
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import ir.vmessenger.network.messaging.RelayDirectory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LongParameterList")
class NetworkCoordinator @Inject constructor(
    private val joinNetworkUseCase: JoinNetworkUseCase,
    private val publishNetworkEndpointsUseCase: PublishNetworkEndpointsUseCase,
    private val messagingService: MessagingService,
    private val incomingMessageCollector: IncomingMessageCollector,
    private val outboxDispatcher: OutboxDispatcher,
    private val contactRequestRetryWorker: ContactRequestRetryWorker,
    private val identityRepository: IdentityRepository,
    private val contactDao: ContactDao,
    private val relayDirectory: RelayDirectory,
    private val embeddedDhtService: ir.vmessenger.network.dht.EmbeddedDhtService,
    private val peerRelayCoordinator: PeerRelayCoordinator,
    private val p2pConfigLoader: P2PConfigLoader,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

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

    private fun registerConnectivityCallback() {
        if (connectivityRegistered) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.registerDefaultNetworkCallback(connectivityCallback) }
            .onSuccess { connectivityRegistered = true }
            .onFailure { AppLogger.warn("Network", "connectivity callback register failed: ${it.message}") }
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
        val (identity, privateKey) = awaitIdentityWithKey()
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
            identityHash = identity.identityHash,
            identityPub = identity.ed25519PublicKey,
            ed25519PrivateKey = privateKey,
        )
        AppLogger.info("Network", "relay listener starting")
        if (ir.vmessenger.core.common.network.P2PConfig.relayPeerModeEnabled) {
            peerRelayCoordinator.logStatus()
        }
        // Listener is up and endpoints are published — retry any queued messages now.
        outboxDispatcher.retryNow()
    }

    private suspend fun awaitIdentityWithKey(): Pair<Identity, ByteArray> {
        var loggedWait = false
        while (true) {
            val identity = identityRepository.getIdentity()
            val privateKey = if (identity != null) identityRepository.getEd25519PrivateKey() else null
            if (identity != null && privateKey != null) {
                if (loggedWait) {
                    AppLogger.info("Network", "identity and keys ready for publish/relay")
                }
                return identity to privateKey
            }
            if (!loggedWait) {
                AppLogger.info("Network", "waiting for identity and keys before publish/relay")
                loggedWait = true
            }
            if (identity == null) {
                identityRepository.observeIdentity().filterNotNull().first()
            } else {
                delay(KEY_POLL_MS)
            }
        }
    }

    private suspend fun configureInbound() {
        messagingService.configureInbound(
            selfProvider = self@{
                val identity = identityRepository.getIdentity() ?: return@self null
                PeerIdentity(
                    identityHash = identity.identityHash,
                    ed25519PublicKey = identity.ed25519PublicKey,
                    x25519StaticPublicKey = identity.x25519StaticPublicKey,
                    ed25519PrivateKey = identityRepository.getEd25519PrivateKey(),
                    x25519StaticPrivateKey = identityRepository.getX25519StaticPrivateKey(),
                )
            },
            resolveInboundPeer = resolveInboundPeer@{ identityPub, staticPub ->
                val hash = UserHashEncoder.identityHashFromPublicKey(identityPub)
                val contact = contactDao.findContactForInbound(identityPub, hash)
                if (contact != null) {
                    PeerIdentity(
                        identityHash = hash,
                        ed25519PublicKey = identityPub,
                        x25519StaticPublicKey = staticPub,
                    )
                } else {
                    AppLogger.info(
                        "Contact",
                        "inbound stranger hash=${IdentityHashMatcher.hashPrefixHex(hash)}",
                    )
                    PeerIdentity(
                        identityHash = hash,
                        ed25519PublicKey = identityPub,
                        x25519StaticPublicKey = staticPub,
                    )
                }
            },
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
        )
    }

    companion object {
        private const val KEY_POLL_MS = 100L
    }
}
