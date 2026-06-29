package ir.vmessenger.network.messaging

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.common.network.EndpointSource
import ir.vmessenger.core.common.network.NetworkConfig
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.TransportIds
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.Frame
import ir.vmessenger.core.proto.wire.v1.FrameType
import ir.vmessenger.network.discovery.EndpointResolveService
import ir.vmessenger.network.messaging.EndpointOrder
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingEnvelope(
    val envelope: MessageEnvelope,
    val contactId: String,
    val session: ActiveSecureSession? = null,
)

@Singleton
class MessagingService @Inject constructor(
    private val endpointResolveService: ir.vmessenger.network.discovery.EndpointResolveService,
    private val sessionPostHandshakeHandler: SessionPostHandshakeHandler,
    private val transportSelector: TransportSelector,
    private val secureChannelFactory: SecureChannelFactory,
    private val internetTransport: InternetTransport,
    private val relayListener: RelayListener,
) : InboundConnectionHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboundSessions = mutableMapOf<String, ActiveSecureSession>()
    private val sessionMutex = Mutex()
    private val sendMutexes = mutableMapOf<String, Mutex>()
    private val postHandshakeDone = ConcurrentHashMap.newKeySet<String>()
    private val inboundReadContacts = ConcurrentHashMap.newKeySet<String>()
    private val _incoming = MutableSharedFlow<IncomingEnvelope>(extraBufferCapacity = 64)
    val incoming: Flow<IncomingEnvelope> = _incoming.asSharedFlow()

    private var selfProvider: (suspend () -> PeerIdentity?)? = null
    private var resolveInboundPeer: (suspend (ByteArray, ByteArray) -> PeerIdentity?)? = null
    private var contactIdResolver: (suspend (ByteArray) -> String?)? = null
    private var peerKeyUpdater: (suspend (String, PeerIdentity) -> Unit)? = null

    fun configureInbound(
        selfProvider: suspend () -> PeerIdentity?,
        resolveInboundPeer: suspend (identityPub: ByteArray, staticPub: ByteArray) -> PeerIdentity?,
        contactIdResolver: suspend (ByteArray) -> String?,
        peerKeyUpdater: (suspend (String, PeerIdentity) -> Unit)? = null,
    ) {
        this.selfProvider = selfProvider
        this.resolveInboundPeer = resolveInboundPeer
        this.contactIdResolver = contactIdResolver
        this.peerKeyUpdater = peerKeyUpdater
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
        val resolvePeer = resolveInboundPeer ?: return
        val resolveContactId = contactIdResolver ?: return
        val sessionResult = secureChannelFactory.acceptResolving(connection, self, resolvePeer)
        val session = sessionResult.getOrElse {
            AppLogger.warn("Messaging", "inbound handshake failed: ${it.message}")
            connection.close()
            return
        }
        val activeSession = session as ActiveSecureSession
        val peerHash = session.peer.identityHash
        val contactId = resolveContactId(peerHash) ?: run {
            AppLogger.warn(
                "Messaging",
                "inbound contact id missing hash=${IdentityHashMatcher.hashPrefixHex(peerHash)}",
            )
            connection.close()
            return
        }
        scope.launch {
            runCatching { peerKeyUpdater?.invoke(contactId, session.peer) }
                .onFailure { AppLogger.warn("Messaging", "peer key update failed: ${it.message}") }
        }
        inboundReadContacts.add(contactId)
        try {
            readSecureFrames(activeSession, contactId, connection)
        } finally {
            inboundReadContacts.remove(contactId)
            postHandshakeDone.remove(contactId)
            activeSession.close()
        }
    }

    suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelope: MessageEnvelope,
    ): AppResult<Unit> {
        val mutex = sendMutexes.getOrPut(contactId) { Mutex() }
        return mutex.withLock {
            when (val resolved = endpointResolveService.resolve(peer.identityHash)) {
                is AppResult.Success -> {
                    val ordered = EndpointOrder.order(resolved.data.endpoints)
                    if (ordered.isEmpty()) {
                        AppResult.Error(AppError.Network("endpoint یافت نشد"))
                    } else {
                        sendWithFallback(
                            contactId,
                            self,
                            peer,
                            ordered,
                            envelope,
                            fromPeerCache = resolved.data.fromPeerCache,
                        )
                    }
                }
                is AppResult.Error -> resolved
            }
        }
    }

    private suspend fun sendWithFallback(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoints: List<Endpoint>,
        envelope: MessageEnvelope,
        fromPeerCache: Boolean = false,
    ): AppResult<Unit> {
        var lastError: AppError? = null
        val attemptSource = if (fromPeerCache) EndpointSource.CACHE else EndpointSource.DHT
        for (endpoint in endpoints) {
            AppLogger.info("Messaging", "try ${endpoint.transport.value}:${endpoint.address}")
            awaitCloseOutbound(contactId)
            when (val result = sendToEndpoint(contactId, self, peer, endpoint, envelope)) {
                is AppResult.Success -> {
                    AppLogger.info("Messaging", "sent via ${endpoint.transport.value}")
                    NetworkPathTracker.recordAttempt(
                        endpoint = "${endpoint.transport.value}:${endpoint.address}",
                        source = attemptSource,
                        success = true,
                    )
                    val path = when {
                        fromPeerCache -> NetworkPath.CACHED_PEER
                        else -> endpoint.toNetworkPath()
                    }
                    NetworkPathTracker.record(
                        path = path,
                        detail = "${endpoint.transport.value}:${endpoint.address}",
                    )
                    return result
                }
                is AppResult.Error -> {
                    AppLogger.warn("Messaging", "failed ${endpoint.transport.value}: ${result.error.message}")
                    NetworkPathTracker.recordAttempt(
                        endpoint = "${endpoint.transport.value}:${endpoint.address}",
                        source = attemptSource,
                        success = false,
                        failureReason = result.error.message,
                    )
                    lastError = result.error
                }
            }
        }
        AppLogger.error("Messaging", "all transports failed for contact=$contactId")
        return AppResult.Error(lastError ?: AppError.Network("ارسال ناموفق"))
    }

    suspend fun sendToEndpoint(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoint: Endpoint,
        envelope: MessageEnvelope,
    ): AppResult<Unit> = runCatching {
        val session = sessionMutex.withLock {
            outboundSessions[contactId] ?: establishSession(contactId, self, peer, endpoint).also { established ->
                outboundSessions[contactId] = established
            }
        }
        val sealed = session.seal(envelope.toByteArray())
        val frame = Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(ByteString.copyFrom(sealed))
            .build()
        session.writeFrame(frame.toByteArray())
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = {
            awaitCloseOutbound(contactId)
            AppResult.Error(AppError.Network(it.message ?: "ارسال ناموفق"))
        },
    )

    private suspend fun establishSession(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoint: Endpoint,
    ): ActiveSecureSession {
        val connection = transportSelector.connect(
            endpoint,
            relayTargetId = if (endpoint.transport == TransportIds.RELAY) peer.identityHash else null,
        ).getOrThrow()
        val session = secureChannelFactory.initiate(connection, self, peer).getOrThrow() as ActiveSecureSession
        // Post-handshake protocol (peer exchange, mailbox) runs only on the inbound
        // accept path so ratchet counters stay aligned with the first chat frame.
        scope.launch {
            runCatching { peerKeyUpdater?.invoke(contactId, session.peer) }
                .onFailure { AppLogger.warn("Messaging", "peer key update failed: ${it.message}") }
        }
        scope.launch {
            try {
                readSecureFrames(session, contactId, connection)
            } finally {
                sessionMutex.withLock {
                    if (outboundSessions[contactId] === session) {
                        outboundSessions.remove(contactId)
                    }
                }
                session.close()
            }
        }
        return session
    }

    private suspend fun readSecureFrames(
        session: ActiveSecureSession,
        contactId: String,
        connection: Connection,
    ) {
        connection.read().collect { frameBytes ->
            processSecureFrame(session, contactId, frameBytes)
        }
    }

    private suspend fun processSecureFrame(
        session: ActiveSecureSession,
        contactId: String,
        frameBytes: ByteArray,
    ) {
        val frame = try {
            Frame.parseFrom(frameBytes)
        } catch (e: InvalidProtocolBufferException) {
            AppLogger.warn("Messaging", "inbound frame parse failed contact=$contactId: ${e.message}")
            return
        }
        if (frame.type != FrameType.FRAME_TYPE_SECURE) {
            AppLogger.debug("Messaging", "inbound skip frame type=${frame.type} contact=$contactId")
            return
        }
        val counter = session.ratchetState.recvCounter + 1
        val plaintext = session.open(frame.body.toByteArray(), counter)
        if (plaintext == null) {
            AppLogger.warn(
                "Messaging",
                "inbound decrypt failed contact=$contactId counter=$counter",
            )
            return
        }
        val envelope = try {
            MessageEnvelope.parseFrom(plaintext)
        } catch (e: InvalidProtocolBufferException) {
            AppLogger.warn("Messaging", "inbound envelope parse failed contact=$contactId: ${e.message}")
            return
        }
        _incoming.emit(IncomingEnvelope(envelope = envelope, contactId = contactId, session = session))
        schedulePostHandshakeIfNeeded(contactId, session)
    }

    private fun schedulePostHandshakeIfNeeded(contactId: String, session: ActiveSecureSession) {
        if (!inboundReadContacts.contains(contactId)) return
        if (!postHandshakeDone.add(contactId)) return
        scope.launch {
            val self = selfProvider?.invoke() ?: return@launch
            runCatching { sessionPostHandshakeHandler.onEstablished(session, self, session.peer) }
                .onFailure { AppLogger.warn("Messaging", "post-handshake hook failed: ${it.message}") }
        }
    }

    /**
     * Forwards an already-sealed secure frame to a peer without re-encryption (user relay).
     */
    suspend fun forwardOpaqueFrame(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        sealedSecureBody: ByteArray,
    ): AppResult<Unit> = runCatching {
        when (val resolved = endpointResolveService.resolve(peer.identityHash)) {
            is AppResult.Success -> {
                val ordered = EndpointOrder.order(resolved.data.endpoints)
                var lastError: AppError? = null
                for (endpoint in ordered) {
                    awaitCloseOutbound(contactId)
                    val result = forwardToEndpoint(contactId, self, peer, endpoint, sealedSecureBody)
                    if (result is AppResult.Success) return@runCatching
                    if (result is AppResult.Error) lastError = result.error
                }
                throw IllegalStateException(lastError?.message ?: "relay forward failed")
            }
            is AppResult.Error -> throw IllegalStateException(resolved.error.message)
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Error(AppError.Network(it.message ?: "relay forward failed")) },
    )

    private suspend fun forwardToEndpoint(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoint: Endpoint,
        sealedSecureBody: ByteArray,
    ): AppResult<Unit> = runCatching {
        val session = sessionMutex.withLock {
            outboundSessions[contactId] ?: establishSession(contactId, self, peer, endpoint).also { established ->
                outboundSessions[contactId] = established
            }
        }
        val frame = Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(ByteString.copyFrom(sealedSecureBody))
            .build()
        session.writeFrame(frame.toByteArray())
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = {
            awaitCloseOutbound(contactId)
            AppResult.Error(AppError.Network(it.message ?: "relay forward failed"))
        },
    )

    suspend fun sendProtocolReply(session: ActiveSecureSession, reply: MessageEnvelope) {
        val sealed = session.seal(reply.toByteArray())
        val frame = Frame.newBuilder()
            .setVersion(1)
            .setType(FrameType.FRAME_TYPE_SECURE)
            .setBody(ByteString.copyFrom(sealed))
            .build()
        session.writeFrame(frame.toByteArray())
    }

    private suspend fun awaitCloseOutbound(contactId: String) {
        val previous = sessionMutex.withLock { outboundSessions.remove(contactId) }
        previous?.close()
    }
}

/**
 * Classifies the transport path a successful send used so debug tooling can show
 * whether traffic took a direct, relay, or user/community relay route. Direct
 * INTERNET endpoints are reported as [NetworkPath.DIRECT]; relay endpoints are
 * split into the central default relay versus a user/community relay.
 */
internal fun Endpoint.toNetworkPath(): NetworkPath = when (transport) {
    TransportIds.INTERNET -> NetworkPath.DIRECT
    TransportIds.UDP -> NetworkPath.UDP_ATTEMPT
    TransportIds.RELAY ->
        if (address == NetworkConfig.DEFAULT_RELAY_URL) NetworkPath.DEFAULT_RELAY else NetworkPath.USER_RELAY
    else -> NetworkPath.UNKNOWN
}
