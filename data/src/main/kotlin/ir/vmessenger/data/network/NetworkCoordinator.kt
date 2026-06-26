package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.data.repository.findByIdentityHash
import ir.vmessenger.data.repository.updateLearnedKeys
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.usecase.discovery.JoinNetworkUseCase
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
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
    private val identityRepository: IdentityRepository,
    private val contactDao: ContactDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun start(
        listenPort: Int,
        directHost: String? = null,
        directPort: Int? = null,
    ) {
        scope.launch {
            AppLogger.info("Network", "coordinator start listenPort=$listenPort dev=${directHost != null}")
            configureInbound()
            incomingMessageCollector.start()
            messagingService.startListening(listenPort)
            AppLogger.info("Network", "TCP listener started on $listenPort")
            when (val join = joinNetworkUseCase()) {
                is AppResult.Success ->
                    AppLogger.info("Network", "join network OK")
                is AppResult.Error ->
                    AppLogger.error("Network", "join network failed: ${join.error.message}")
            }
            publishAndStartRelay(directHost = directHost, directPort = directPort)
        }
    }

    private suspend fun publishAndStartRelay(
        directHost: String?,
        directPort: Int?,
    ) {
        val (identity, privateKey) = awaitIdentityWithKey()
        when (
            val publish = publishNetworkEndpointsUseCase(directHost = directHost, directPort = directPort)
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
            peerResolver = peer@{ identityHash ->
                val contact = contactDao.findByIdentityHash(identityHash) ?: return@peer null
                PeerIdentity(
                    identityHash = contact.identityHash,
                    ed25519PublicKey = contact.ed25519Public,
                    x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(32),
                )
            },
            contactIdResolver = { identityHash ->
                contactDao.findByIdentityHash(identityHash)?.id
            },
            peerKeyUpdater = { contactId, peer ->
                contactDao.updateLearnedKeys(
                    contactId = contactId,
                    identityHash = peer.identityHash,
                    ed25519Public = peer.ed25519PublicKey,
                    x25519StaticPublic = peer.x25519StaticPublicKey,
                )
                AppLogger.info("Contact", "learned peer keys for contact=$contactId")
            },
        )
    }

    companion object {
        private const val KEY_POLL_MS = 100L
    }
}
