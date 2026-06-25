package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.network.discovery.DiscoveryManager
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.InternetTransport
import ir.vmessenger.network.transport.TransportSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingEnvelope(
    val envelope: MessageEnvelope,
    val contactId: String,
)

@Singleton
class MessagingService @Inject constructor(
    private val discoveryManager: DiscoveryManager,
    private val transportSelector: TransportSelector,
    private val secureChannelFactory: SecureChannelFactory,
    private val internetTransport: InternetTransport,
    private val relayListener: RelayListener,
    private val cryptoEngine: CryptoEngine,
) : InboundConnectionHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, SecureSession>()
    private val sessionMutex = Mutex()
    private val _incoming = MutableSharedFlow<IncomingEnvelope>(extraBufferCapacity = 64)
    val incoming: Flow<IncomingEnvelope> = _incoming.asSharedFlow()

    private var selfProvider: (suspend () -> PeerIdentity?)? = null
    private var peerResolver: (suspend (ByteArray) -> PeerIdentity?)? = null
    private var contactIdResolver: (suspend (ByteArray) -> String?)? = null

    fun configureInbound(
        selfProvider: suspend () -> PeerIdentity?,
        peerResolver: suspend (ByteArray) -> PeerIdentity?,
        contactIdResolver: suspend (ByteArray) -> String?,
    ) {
        this.selfProvider = selfProvider
        this.peerResolver = peerResolver
        this.contactIdResolver = contactIdResolver
    }

    fun startListening(listenPort: Int) {
        scope.launch {
            internetTransport.listen(listenPort).collect { connection ->
                launch { acceptInbound(connection) }
            }
        }
    }

    fun startRelayListener(
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKey: ByteArray,
    ) {
        relayListener.configure(identityHash, identityPub, ed25519PrivateKey, this)
        relayListener.start()
    }

    override suspend fun onInboundConnection(connection: Connection) {
        acceptInbound(connection)
    }

    @Suppress("ReturnCount")
    private suspend fun acceptInbound(connection: Connection) {
        val self = selfProvider?.invoke() ?: return
        val resolvePeer = peerResolver ?: return
        val resolveContactId = contactIdResolver ?: return
        val sessionResult = secureChannelFactory.acceptResolving(connection, self) { identityPub, staticPub ->
            val hash = cryptoEngine.sha256(identityPub)
            resolvePeer(hash)?.copy(x25519StaticPublicKey = staticPub)
        }
        val session = sessionResult.getOrElse {
            connection.close()
            return
        }
        val peerHash = session.peer.identityHash
        val contactId = resolveContactId(peerHash) ?: run {
            connection.close()
            return
        }
        sessionMutex.withLock {
            sessions[contactId] = session
        }
        try {
            connection.read().collect { frameBytes ->
                val frame = Frame.parseFrom(frameBytes)
                if (frame.type != FrameType.FRAME_TYPE_SECURE) return@collect
                val counter = (session as ActiveSecureSession).ratchetState.recvCounter + 1
                val plaintext = session.open(frame.body.toByteArray(), counter) ?: return@collect
                val envelope = MessageEnvelope.parseFrom(plaintext)
                _incoming.emit(IncomingEnvelope(envelope, contactId))
            }
        } finally {
            session.close()
            sessionMutex.withLock { sessions.remove(contactId) }
        }
    }

    suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelope: MessageEnvelope,
    ): AppResult<Unit> {
        return when (val endpoints = discoveryManager.resolve(peer.identityHash)) {
            is AppResult.Success -> {
                val ordered = orderEndpoints(endpoints.data)
                if (ordered.isEmpty()) {
                    AppResult.Error(AppError.Network("endpoint یافت نشد"))
                } else {
                    sendWithFallback(contactId, self, peer, ordered, envelope)
                }
            }
            is AppResult.Error -> endpoints
        }
    }

    private suspend fun sendWithFallback(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoints: List<Endpoint>,
        envelope: MessageEnvelope,
    ): AppResult<Unit> {
        var lastError: AppError? = null
        for (endpoint in endpoints) {
            sessionMutex.withLock { sessions.remove(contactId) }
            when (val result = sendToEndpoint(contactId, self, peer, endpoint, envelope)) {
                is AppResult.Success -> return result
                is AppResult.Error -> lastError = result.error
            }
        }
        return AppResult.Error(lastError ?: AppError.Network("ارسال ناموفق"))
    }

    private fun orderEndpoints(endpoints: List<Endpoint>): List<Endpoint> =
        endpoints.sortedBy { endpoint ->
            when (endpoint.transport) {
                TransportIds.INTERNET -> 0
                TransportIds.RELAY -> 1
                else -> 2
            }
        }

    suspend fun sendToEndpoint(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoint: Endpoint,
        envelope: MessageEnvelope,
    ): AppResult<Unit> = runCatching {
        val session = sessionMutex.withLock {
            sessions[contactId] ?: establishSession(self, peer, endpoint).also {
                sessions[contactId] = it
            }
        }
        val sealed = session.seal(envelope.toByteArray())
        val frame = Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(ByteString.copyFrom(sealed))
            .build()
        (session as ActiveSecureSession).writeFrame(frame.toByteArray())
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = {
            AppResult.Error(AppError.Network(it.message ?: "ارسال ناموفق"))
        },
    )

    private suspend fun establishSession(
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoint: Endpoint,
    ): SecureSession {
        val connection = transportSelector.connect(
            endpoint,
            relayTargetId = if (endpoint.transport == TransportIds.RELAY) peer.identityHash else null,
        ).getOrThrow()
        return secureChannelFactory.initiate(connection, self, peer).getOrThrow()
    }
}

@Singleton
class OutboxWorker @Inject constructor(
    private val messagingService: MessagingService,
) {
    suspend fun processDue(
        items: List<ir.vmessenger.core.database.entity.OutboxEntity>,
        self: PeerIdentity,
        peerResolver: (String) -> PeerIdentity?,
        envelopeBuilder: (ir.vmessenger.core.database.entity.OutboxEntity) -> MessageEnvelope?,
    ) {
        items.forEach { item ->
            val peer = peerResolver(item.conversationId)
            val envelope = peer?.let { envelopeBuilder(item) }
            if (peer != null && envelope != null) {
                messagingService.send(item.conversationId, self, peer, envelope)
            }
        }
    }
}
