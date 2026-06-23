package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.network.discovery.DiscoveryManager
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, SecureSession>()
    private val sessionMutex = Mutex()
    private val _incoming = MutableSharedFlow<IncomingEnvelope>(extraBufferCapacity = 64)
    val incoming: Flow<IncomingEnvelope> = _incoming.asSharedFlow()

    fun startListening(listenPort: Int) {
        scope.launch {
            internetTransport.listen(listenPort).collect { connection ->
                launch { handleInboundConnection() }
            }
        }
    }

    @Suppress("EmptyFunctionBlock")
    private suspend fun handleInboundConnection() {
        // Inbound accept path is completed when identity context is bound to the service.
    }

    suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelope: MessageEnvelope,
    ): AppResult<Unit> {
        return when (val endpoints = discoveryManager.resolve(peer.identityHash)) {
            is AppResult.Success -> {
                val endpoint = endpoints.data.firstOrNull()
                if (endpoint == null) {
                    AppResult.Error(AppError.Network("endpoint یافت نشد"))
                } else {
                    sendToEndpoint(contactId, self, peer, endpoint, envelope)
                }
            }
            is AppResult.Error -> endpoints
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
        val connection = transportSelector.connect(endpoint).getOrThrow()
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
