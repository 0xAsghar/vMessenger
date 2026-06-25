package ir.vmessenger.data.network

import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.usecase.discovery.JoinNetworkUseCase
import ir.vmessenger.domain.usecase.discovery.PublishNetworkEndpointsUseCase
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
            joinNetworkUseCase()
            publishNetworkEndpointsUseCase(directHost = directHost, directPort = directPort)
            configureInbound()
            incomingMessageCollector.start()
            messagingService.startListening(listenPort)
            startRelayListener()
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
                val contact = contactDao.getByIdentityHash(identityHash) ?: return@peer null
                PeerIdentity(
                    identityHash = contact.identityHash,
                    ed25519PublicKey = contact.ed25519Public,
                    x25519StaticPublicKey = contact.ed25519Public,
                )
            },
            contactIdResolver = { identityHash ->
                contactDao.getByIdentityHash(identityHash)?.id
            },
        )
    }

    private suspend fun startRelayListener() {
        val identity = identityRepository.getIdentity() ?: return
        val privateKey = identityRepository.getEd25519PrivateKey() ?: return
        messagingService.startRelayListener(
            identityHash = identity.identityHash,
            identityPub = identity.ed25519PublicKey,
            ed25519PrivateKey = privateKey,
        )
    }
}
