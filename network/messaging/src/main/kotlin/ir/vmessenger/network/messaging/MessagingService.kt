package ir.vmessenger.network.messaging

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.KeyedMutex
import ir.vmessenger.core.common.concurrency.KeyedSlot
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.Endpoint
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.wire.v1.CloseCode
import ir.vmessenger.network.discovery.EndpointResolveService
import ir.vmessenger.network.transport.Connection
import ir.vmessenger.network.transport.ConnectionState
import ir.vmessenger.network.transport.InternetTransport
import ir.vmessenger.network.transport.TransportSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class IncomingEnvelope(
    val envelope: MessageEnvelope,
    val contactId: String,
    val session: ActiveSecureSession? = null,
)

/**
 * Per-contact outbound state: the slot's mutex serializes dial/handshake/write
 * for one contact, and [session] is the open outbound session (if any). The
 * slot lives while a send holds it or a session is open, and disappears from the
 * [KeyedMutex] once both are gone.
 */
class SessionSlot : KeyedSlot() {
    private val ref = AtomicReference<ActiveSecureSession?>(null)

    val session: ActiveSecureSession?
        get() = ref.get()

    internal fun install(session: ActiveSecureSession) = ref.set(session)

    /** Detaches and returns the current session (the caller closes it). */
    internal fun take(): ActiveSecureSession? = ref.getAndSet(null)

    /** Detaches [session] only if it is still the installed one. */
    internal fun clear(session: ActiveSecureSession): Boolean = ref.compareAndSet(session, null)
}

@Singleton
@Suppress("TooManyFunctions") // outbound API, inbound accept path and session lifecycle are one state machine
class MessagingService @Inject constructor(
    private val endpointResolveService: EndpointResolveService,
    private val sessionPostHandshakeHandler: SessionPostHandshakeHandler,
    transportSelector: TransportSelector,
    private val secureChannelFactory: SecureChannelFactory,
    private val internetTransport: InternetTransport,
    private val relayListener: RelayListener,
) : InboundConnectionHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(TAG))
    private val frameGuard = SecureFrameGuard()
    private val dialer = OutboundDialer(transportSelector, secureChannelFactory)
    private val slots = KeyedMutex<String, SessionSlot> { SessionSlot() }
    private val inboundPermits = Semaphore(MAX_UNAUTHENTICATED_INBOUND)
    private val postHandshakeDone = ConcurrentHashMap.newKeySet<String>()
    private val inboundReadContacts = ConcurrentHashMap.newKeySet<String>()

    /** Live inbound sessions by the contact that dialed us, so [closeSessions] can reach them. */
    private val inboundSessions = ConcurrentHashMap<ActiveSecureSession, String>()

    // Authenticated envelopes go straight from each session's read loop into
    // the installed sink (the per-contact inbound router), so a slow consumer
    // for one contact only suspends *that* session's read loop. The bounded
    // channel is a fallback for frames that arrive while no sink is installed
    // (before the collector starts, or between stop and restart); the
    // collector drains it when it installs itself.
    @Volatile
    private var incomingSink: (suspend (IncomingEnvelope) -> Unit)? = null
    private val _incoming = Channel<IncomingEnvelope>(INCOMING_BUFFER)
    val incoming: Flow<IncomingEnvelope> = _incoming.receiveAsFlow()

    @Volatile
    private var listenJob: Job? = null

    private var selfProvider: (suspend () -> PeerIdentity?)? = null
    private var resolveInboundPeer: (suspend (ByteArray, ByteArray) -> PeerIdentity?)? = null
    private var contactIdResolver: (suspend (ByteArray) -> String?)? = null
    private var isProvisionalContactId: ((String) -> Boolean)? = null
    private var peerKeyUpdater: (suspend (String, PeerIdentity) -> Unit)? = null
    private var peerKeyChangeRecorder: (suspend (contactId: String, newStaticKey: ByteArray) -> Unit)? = null

    /** Everything one outbound send needs: who, over which slot, and how to open a session. */
    private class SendContext(
        val contactId: String,
        val peer: PeerIdentity,
        val slot: SessionSlot,
        val open: suspend () -> Result<ActiveSecureSession>,
    )

    /**
     * [peerKeyChangeRecorder] runs when a contact we dial presents an X25519
     * static key other than the pinned one (initiator side of key pinning); the
     * responder side records it in `resolveInboundPeer`. Both must persist the
     * new key as *pending* so the user can re-verify and accept it.
     */
    // Independent optional hooks for one configuration seam; grouping them into a
    // holder would only move the same list behind another type.
    @Suppress("LongParameterList")
    fun configureInbound(
        selfProvider: suspend () -> PeerIdentity?,
        resolveInboundPeer: suspend (identityPub: ByteArray, staticPub: ByteArray) -> PeerIdentity?,
        contactIdResolver: suspend (ByteArray) -> String?,
        peerKeyUpdater: (suspend (String, PeerIdentity) -> Unit)? = null,
        peerKeyChangeRecorder: (suspend (contactId: String, newStaticKey: ByteArray) -> Unit)? = null,
        isProvisionalContactId: ((String) -> Boolean)? = null,
    ) {
        this.selfProvider = selfProvider
        this.resolveInboundPeer = resolveInboundPeer
        this.contactIdResolver = contactIdResolver
        this.isProvisionalContactId = isProvisionalContactId
        this.peerKeyUpdater = peerKeyUpdater
        this.peerKeyChangeRecorder = peerKeyChangeRecorder
    }

    /**
     * Installs (or, with `null`, removes) the consumer every authenticated
     * envelope is handed to from the session read loop that decrypted it. The
     * sink is called on that loop: suspending in it applies backpressure to
     * that one session only.
     */
    fun setIncomingSink(sink: (suspend (IncomingEnvelope) -> Unit)?) {
        incomingSink = sink
    }

    /**
     * Starts (or restarts) the TCP listener. Bind/accept failures never kill the
     * service: they are logged and the listener is retried with backoff.
     */
    fun startListening(listenPort: Int) {
        listenJob?.cancel()
        listenJob = scope.launch { listenLoop(listenPort) }
    }

    private suspend fun listenLoop(port: Int) {
        var retryMs = LISTEN_RETRY_MS
        while (currentCoroutineContext().isActive) {
            val failure = runCatching {
                internetTransport.listen(port).collect { connection ->
                    scope.launch { acceptInbound(connection) }
                }
            }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val reason = failure?.message ?: "listener ended"
            AppLogger.warn(TAG, "TCP listener failed on port $port: $reason, retry in ${retryMs / 1000}s")
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(LISTEN_RETRY_MAX_MS)
        }
    }

    /** [ed25519PrivateKeyProvider] is asked for the signing key per relay hello; nothing here stores it. */
    fun startRelayListener(
        identityHash: ByteArray,
        identityPub: ByteArray,
        ed25519PrivateKeyProvider: suspend () -> ByteArray?,
    ) {
        relayListener.configure(identityHash, identityPub, ed25519PrivateKeyProvider, this)
        relayListener.start()
    }

    override suspend fun onInboundConnection(connection: Connection) {
        acceptInbound(connection)
    }

    /**
     * Handshake and serve one inbound connection. At most
     * [MAX_UNAUTHENTICATED_INBOUND] connections may be mid-handshake at once;
     * beyond that new ones are dropped outright rather than queued.
     */
    internal suspend fun acceptInbound(connection: Connection) {
        if (!inboundPermits.tryAcquire()) {
            AppLogger.warn(TAG, "inbound handshake limit ($MAX_UNAUTHENTICATED_INBOUND) reached; dropping connection")
            connection.close()
            return
        }
        val accepted = try {
            authenticateInbound(connection)
        } finally {
            inboundPermits.release()
        }
        if (accepted != null) serveInbound(accepted.first, accepted.second, connection)
    }

    @Suppress("ReturnCount") // each early exit is a distinct rejection of an unauthenticated peer
    private suspend fun authenticateInbound(connection: Connection): Pair<ActiveSecureSession, String>? {
        val self = selfProvider?.invoke()
        val resolvePeer = resolveInboundPeer
        val resolveContactId = contactIdResolver
        if (self == null || resolvePeer == null || resolveContactId == null) {
            connection.close()
            return null
        }
        val session = secureChannelFactory.acceptResolving(connection, self, resolvePeer).getOrElse {
            AppLogger.warn(TAG, "inbound handshake failed: ${it.message}")
            connection.close()
            return null
        } as ActiveSecureSession
        val peerHash = session.peer.identityHash
        val contactId = resolveContactId(peerHash) ?: run {
            AppLogger.warn(TAG, "inbound contact id missing hash=${IdentityHashMatcher.hashPrefixHex(peerHash)}")
            session.close()
            return null
        }
        return session to contactId
    }

    private suspend fun serveInbound(session: ActiveSecureSession, contactId: String, connection: Connection) {
        scope.launch {
            runCatching { peerKeyUpdater?.invoke(contactId, session.peer) }
                .onFailure { AppLogger.warn(TAG, "peer key update failed: ${it.message}") }
        }
        inboundReadContacts.add(contactId)
        inboundSessions[session] = contactId
        try {
            readSecureFrames(session, contactId, connection)
        } finally {
            // The id may have been rebound mid-session (stranger -> approved
            // contact), so clean up whichever one this session ended on.
            val finalContactId = inboundSessions.remove(session) ?: contactId
            inboundReadContacts.remove(finalContactId)
            postHandshakeDone.remove(finalContactId)
            session.close()
        }
    }

    /**
     * Tears down every live session with [contactId] — the outbound one and any
     * inbound ones — after telling the peer `CLOSE{REJECTED}`. Used when the user
     * blocks or deletes the contact; a later send re-handshakes from scratch.
     */
    suspend fun closeSessions(contactId: String) {
        val outbound = slots.peek(contactId)?.take()
        val inbound = inboundSessions.filterValues { it == contactId }.keys
        val sessions = listOfNotNull(outbound) + inbound
        for (session in sessions) {
            runCatching {
                session.writeClose(CloseCode.CLOSE_CODE_REJECTED, "session closed by user")
                session.close()
            }.onFailure { AppLogger.warn(TAG, "session close failed contact=$contactId: ${it.message}") }
        }
        if (sessions.isNotEmpty()) {
            AppLogger.info(TAG, "closed ${sessions.size} session(s) contact=$contactId")
        }
    }

    /** Stops the TCP listener and closes every session (secure wipe / coordinator stop). */
    suspend fun closeAll() {
        listenJob?.cancel()
        listenJob = null
        val contacts = slots.keys() + inboundSessions.values.toSet()
        for (contactId in contacts) closeSessions(contactId)
    }

    suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelope: MessageEnvelope,
        forceReconnect: Boolean = false,
    ): AppResult<Unit> =
        sendBatch(contactId, self, peer, sequenceOf(envelope), onSent = {}, forceReconnect = forceReconnect)

    /**
     * Streams [envelopes] to [contactId] over ONE session: the contact's slot is
     * held for the whole batch, the open session is reused (or a single dial +
     * handshake opens one) and every envelope is written in order. [onSent] is
     * invoked with the index of each envelope after its write succeeded, so on
     * an error the caller knows exactly where to resume. Sends to other contacts
     * are never blocked by this one.
     */
    @Suppress("LongParameterList") // the batch API mirrors send() plus the progress callback
    suspend fun sendBatch(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelopes: Sequence<MessageEnvelope>,
        onSent: (Int) -> Unit,
        forceReconnect: Boolean = false,
    ): AppResult<Unit> = slots.withLock(contactId) { slot ->
        // Retries that force a reconnect (e.g. receipt-wait re-sends) skip reuse
        // so they don't keep hitting a silently-dead session.
        if (forceReconnect) slot.take()?.close()
        val context = SendContext(contactId, peer, slot) { resolveAndDial(contactId, self, peer) }
        streamEnvelopes(context, envelopes, onSent)
    }

    /** Sends one envelope over a session to the given [endpoint] (no discovery); reuses an open session. */
    suspend fun sendToEndpoint(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        endpoint: Endpoint,
        envelope: MessageEnvelope,
    ): AppResult<Unit> = slots.withLock(contactId) { slot ->
        val context = SendContext(contactId, peer, slot) { dialer.dialEndpoint(self, peer, endpoint) }
        streamEnvelopes(context, sequenceOf(envelope), onSent = {})
    }

    @Suppress("ReturnCount") // three exits: dial failure, write failure, batch complete
    private suspend fun streamEnvelopes(
        context: SendContext,
        envelopes: Sequence<MessageEnvelope>,
        onSent: (Int) -> Unit,
    ): AppResult<Unit> {
        var session = reusableSession(context)
        var reused = session != null
        var index = 0
        var pending: MessageEnvelope? = null
        val iterator = envelopes.iterator()
        while (pending != null || iterator.hasNext()) {
            val envelope = pending ?: iterator.next()
            pending = null
            val current = session ?: when (val opened = openSession(context)) {
                is AppResult.Success -> opened.data.also {
                    session = it
                    reused = false
                }
                is AppResult.Error -> return opened
            }
            val written = runCatching { withTimeout(WRITE_TIMEOUT_MS) { current.writeSealed(envelope) } }
            when {
                written.isSuccess -> {
                    if (index == 0) AppLogger.info(TAG, "sent via ${current.connection.remote.transport.value}")
                    onSent(index)
                    index++
                }
                reused && index == 0 -> {
                    // The cached session was dead; dial once and retry the same envelope.
                    dropSession(context.slot, current)
                    pending = envelope
                    session = null
                    reused = false
                }
                else -> {
                    dropSession(context.slot, current)
                    val reason = written.exceptionOrNull()?.message
                    AppLogger.warn(TAG, "write failed contact=${context.contactId} index=$index: $reason")
                    return AppResult.Error(AppError.Network(SEND_FAILED_MESSAGE))
                }
            }
        }
        return AppResult.Success(Unit)
    }

    /**
     * The open, unexpired outbound session in the slot, or null. An expired
     * session (65 536 frames / 12 h) is closed here so this send re-handshakes.
     */
    private suspend fun reusableSession(context: SendContext): ActiveSecureSession? {
        val session = context.slot.session
            ?.takeIf { it.connection.state.value == ConnectionState.OPEN }
            ?: return null
        return if (session.isExpired()) {
            AppLogger.info(
                TAG,
                "session expired contact=${context.contactId} frames=${session.frameCount}; re-handshake",
            )
            dropSession(context.slot, session)
            null
        } else {
            AppLogger.info(TAG, "reused session for contact=${context.contactId}")
            session
        }
    }

    private suspend fun openSession(context: SendContext): AppResult<ActiveSecureSession> =
        context.open().fold(
            onSuccess = { session -> AppResult.Success(attachSession(context, session)) },
            onFailure = { cause ->
                if (cause is PeerKeyChangedException) recordPeerKeyChange(context.contactId, cause)
                AppResult.Error(cause.toAppError())
            },
        )

    private suspend fun resolveAndDial(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
    ): Result<ActiveSecureSession> {
        val resolved = when (val result = endpointResolveService.resolve(peer.identityHash)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> {
                AppLogger.warn(TAG, "resolve failed: network contact=$contactId (${result.error.message})")
                return Result.failure(ResolveException(AppError.Network(NETWORK_UNAVAILABLE_MESSAGE)))
            }
        }
        val ordered = EndpointOrder.order(resolved.endpoints)
        val dialed = if (ordered.isEmpty()) {
            AppLogger.warn(TAG, "resolve: not found contact=$contactId")
            Result.failure(ResolveException(AppError.NotFound(ENDPOINT_NOT_FOUND_MESSAGE)))
        } else {
            dialer.dialAny(contactId, self, peer, ordered, resolved.fromPeerCache)
        }
        // If the cached endpoint failed, the peer likely moved (IP/relay changed).
        // Drop the cache so the next attempt re-resolves the freshly republished
        // record from the DHT instead of retrying the stale endpoint until TTL.
        if (dialed.isFailure && resolved.fromPeerCache) endpointResolveService.invalidate(peer.identityHash)
        return networkFailureIfDiscoveryFailed(dialed, resolved, contactId)
    }

    /**
     * Every discovery provider failed (DHT/bootstrap unreachable) and the relay
     * fallback did not reach the peer either: report a network problem, distinct
     * from a peer that has no published record. Handshake-level failures (version,
     * key change) keep their own error so the caller can react to them.
     */
    private fun networkFailureIfDiscoveryFailed(
        dialed: Result<ActiveSecureSession>,
        resolved: EndpointResolveService.Resolved,
        contactId: String,
    ): Result<ActiveSecureSession> {
        val cause = dialed.exceptionOrNull()
        val handshakeLevel = cause is ProtocolVersionException || cause is PeerKeyChangedException
        if (cause == null || !resolved.discoveryFailed || handshakeLevel) return dialed
        AppLogger.warn(TAG, "resolve failed: network contact=$contactId (relay fallback also failed)")
        return Result.failure(ResolveException(AppError.Network(NETWORK_UNAVAILABLE_MESSAGE)))
    }

    /**
     * Installs a freshly handshaken outbound session in the slot and starts its
     * read loop. The loop holds a reference on the slot, so the slot (and the
     * session) outlive the send that opened it; when the loop ends the session
     * is detached, closed and the reference released.
     */
    private fun attachSession(context: SendContext, session: ActiveSecureSession): ActiveSecureSession {
        val contactId = context.contactId
        context.slot.install(session)
        val retained = slots.retain(contactId)
        scope.launch {
            runCatching { peerKeyUpdater?.invoke(contactId, session.peer) }
                .onFailure { AppLogger.warn(TAG, "peer key update failed: ${it.message}") }
        }
        scope.launch {
            try {
                readSecureFrames(session, contactId, session.connection)
            } finally {
                retained.clear(session)
                session.close()
                slots.release(contactId, retained)
            }
        }
        return session
    }

    private suspend fun dropSession(slot: SessionSlot, session: ActiveSecureSession) {
        slot.clear(session)
        session.close()
    }

    /** Initiator-side pin mismatch: persist the presented key as pending (never adopt it). */
    private suspend fun recordPeerKeyChange(contactId: String, cause: PeerKeyChangedException) {
        AppLogger.warn(TAG, "key change pending contact=$contactId (outbound handshake refused)")
        runCatching { peerKeyChangeRecorder?.invoke(contactId, cause.newStaticKey) }
            .onFailure { AppLogger.warn(TAG, "pending key change not recorded: ${it.message}") }
    }

    private suspend fun readSecureFrames(
        session: ActiveSecureSession,
        contactId: String,
        connection: Connection,
    ) {
        // The contact id is resolved once at handshake time, but a peer that was
        // an unknown stranger then can become a real contact while this session
        // is still open (the user approves the request it just sent). Re-resolve
        // for as long as the id is provisional, otherwise every later frame is
        // attributed to the stranger and dropped as "non-approved".
        var currentContactId = contactId
        // Stop at the first frame that arrives after the session closed (peer
        // CLOSE, version mismatch, frame cap): whatever the transport still has
        // buffered must never reach the wiped ratchet.
        connection.read()
            .takeWhile { !session.isClosed }
            .collect { frameBytes ->
                currentContactId = refreshProvisionalContactId(session, currentContactId)
                processSecureFrame(session, currentContactId, frameBytes)
            }
    }

    /**
     * Returns the contact id to attribute the next inbound frame to, re-reading
     * it from the resolver while [current] is still provisional. Rebinds the
     * session's bookkeeping when the peer has meanwhile become a real contact.
     */
    private suspend fun refreshProvisionalContactId(session: ActiveSecureSession, current: String): String {
        val resolved = current
            .takeIf { isProvisionalContactId?.invoke(it) == true }
            ?.let { contactIdResolver?.invoke(session.peer.identityHash) }
            ?.takeIf { it != current }
            ?: return current
        inboundReadContacts.remove(current)
        inboundReadContacts.add(resolved)
        inboundSessions[session] = resolved
        postHandshakeDone.remove(current)
        AppLogger.info(TAG, "inbound session rebound to contact=$resolved")
        return resolved
    }

    private suspend fun processSecureFrame(
        session: ActiveSecureSession,
        contactId: String,
        frameBytes: ByteArray,
    ) {
        when (val outcome = frameGuard.process(session, contactId, frameBytes)) {
            is SecureFrameOutcome.Envelope -> {
                val incoming = IncomingEnvelope(envelope = outcome.envelope, contactId = contactId, session = session)
                incomingSink?.invoke(incoming) ?: _incoming.send(incoming)
                schedulePostHandshakeIfNeeded(contactId, session)
                if (outcome.sessionExpired) {
                    session.writeClose(CloseCode.CLOSE_CODE_SESSION_EXPIRED, "session frame/age cap reached")
                    session.close()
                }
            }
            is SecureFrameOutcome.Closed ->
                AppLogger.info(TAG, "session closed contact=$contactId: ${outcome.reason}")
            SecureFrameOutcome.Ignored -> Unit
        }
    }

    private fun schedulePostHandshakeIfNeeded(contactId: String, session: ActiveSecureSession) {
        if (!inboundReadContacts.contains(contactId)) return
        if (!postHandshakeDone.add(contactId)) return
        scope.launch {
            val self = selfProvider?.invoke() ?: return@launch
            runCatching { sessionPostHandshakeHandler.onEstablished(session, self, session.peer) }
                .onFailure { AppLogger.warn(TAG, "post-handshake hook failed: ${it.message}") }
        }
    }

    /** Writes a protocol reply on an already-open session (never dials). */
    suspend fun sendProtocolReply(session: ActiveSecureSession, reply: MessageEnvelope) {
        session.writeSealed(reply)
    }

    /**
     * Writes [envelope] on the open, unexpired outbound session for [contactId]
     * without resolving or dialing. False when there is no such session or the
     * write failed; the caller decides whether a full [send] is worth it.
     */
    suspend fun sendOnExistingSession(contactId: String, envelope: MessageEnvelope): Boolean {
        val session = slots.peek(contactId)?.session
            ?.takeIf { it.connection.state.value == ConnectionState.OPEN && !it.isExpired() }
            ?: return false
        return runCatching { withTimeout(WRITE_TIMEOUT_MS) { session.writeSealed(envelope) } }
            .onFailure { AppLogger.info(TAG, "existing session write failed contact=$contactId: ${it.message}") }
            .isSuccess
    }

    /** The open outbound session for [contactId], if any (diagnostics and tests). */
    internal fun outboundSession(contactId: String): ActiveSecureSession? = slots.peek(contactId)?.session

    /** Whether [contactId] currently has a live slot (a send in flight or an open session). */
    internal fun hasOutboundSlot(contactId: String): Boolean = slots.peek(contactId) != null

    /** Carries the resolver's [AppError] through the `Result` returned by the opener. */
    private class ResolveException(val error: AppError) : IllegalStateException(error.message)

    private fun Throwable.toAppError(): AppError = when (this) {
        is ResolveException -> error
        is ProtocolVersionException -> AppError.ProtocolVersion(PEER_APP_OUTDATED_MESSAGE, peerMajor)
        is PeerKeyChangedException -> AppError.Security(PEER_KEY_CHANGED_MESSAGE)
        else -> AppError.Network(message ?: SEND_FAILED_MESSAGE)
    }

    companion object {
        private const val TAG = "Messaging"

        /** Inbound envelopes buffered while no sink is installed (see [setIncomingSink]). */
        const val INCOMING_BUFFER = 256

        /** Concurrent inbound connections allowed to be mid-handshake. */
        const val MAX_UNAUTHENTICATED_INBOUND = 32

        /** Bounds one sealed write on an open session. */
        private const val WRITE_TIMEOUT_MS = 20_000L

        private const val LISTEN_RETRY_MS = 30_000L
        private const val LISTEN_RETRY_MAX_MS = 5 * 60_000L

        /** Shown as the outbox `lastError` when the peer speaks another protocol major. */
        const val PEER_APP_OUTDATED_MESSAGE = "نسخهٔ برنامهٔ مخاطب قدیمی است"

        /** Shown as the outbox `lastError` until the user accepts the contact's new static key. */
        const val PEER_KEY_CHANGED_MESSAGE = "کلید مخاطب تغییر کرده است؛ تأیید مجدد لازم است"

        /** Generic outbox `lastError` when every endpoint failed or a write broke. */
        const val SEND_FAILED_MESSAGE = "ارسال ناموفق"

        /** Outbox `lastError` ([AppError.NotFound]) when discovery answered but the peer has no usable endpoint. */
        const val ENDPOINT_NOT_FOUND_MESSAGE = "endpoint یافت نشد"

        /** Outbox `lastError` ([AppError.Network]) when every discovery provider failed to answer. */
        const val NETWORK_UNAVAILABLE_MESSAGE = "شبکه در دسترس نیست"
    }
}
