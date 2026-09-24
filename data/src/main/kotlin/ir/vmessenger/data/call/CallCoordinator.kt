package ir.vmessenger.data.call

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.proto.app.v1.CallEndpoint
import ir.vmessenger.core.proto.app.v1.CallRejectReason
import ir.vmessenger.core.proto.app.v1.CallSignal
import ir.vmessenger.core.proto.app.v1.CallSignalType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.activity.ActivityLogger
import ir.vmessenger.data.di.DefaultDispatcher
import ir.vmessenger.data.network.MessagingPort
import ir.vmessenger.data.network.SelfIdentityCache
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One call at a time, and the signalling that gets it there.
 *
 * Audio never touches this class. It agrees *that* a call is happening and derives the key the
 * media path will use; the path itself is separate, because the messaging session this signalling
 * rides on expires after a bounded number of frames and a call would outlive it.
 *
 * The media key is a fresh X25519 exchange carried inside the already-authenticated, already-
 * encrypted signalling. That buys two things for one round trip: the peer is whoever the messaging
 * handshake proved they are — no second authentication — and each call gets a key that a later
 * compromise of the long-term keys cannot recover.
 *
 * Exactly one call is allowed. A second invite is answered BUSY rather than queued, because a
 * messenger that silently holds a second ringing call is a messenger that rings at the wrong time.
 */
@Singleton
// One method per signal the protocol defines, plus the local actions; a split would separate the
// state machine from the sends that must accompany each transition. The constructor takes one
// collaborator per thing a call touches, and the dispatcher its timeouts run on.
@Suppress("TooManyFunctions", "LongParameterList")
class CallCoordinator @Inject constructor(
    private val contactDao: ContactDao,
    private val selfIdentityCache: SelfIdentityCache,
    private val messaging: MessagingPort,
    private val crypto: CryptoEngine,
    private val media: CallMediaPort,
    private val activityLogger: ActivityLogger,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val _session = MutableStateFlow<CallSession?>(null)
    private val _ended = MutableSharedFlow<CallEnd>(extraBufferCapacity = 1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + loggingExceptionHandler(TAG))

    /** The one pending deadline of the state the call is in; replaced on every move. See [armTimeout]. */
    private var timeout: Job? = null

    val session: StateFlow<CallSession?> = _session.asStateFlow()

    /**
     * Endings the caller should hear about. A call screen used to simply close on an invite that
     * never left, a decline, a busy line or no answer — the same silent exit for every one, after
     * a minute of "Calling…" in the first case.
     */
    val ended: SharedFlow<CallEnd> = _ended.asSharedFlow()

    /** Private to this class by design; see [CallSession]. */
    private var mediaKey: ByteArray? = null
    private var ephemeral: ir.vmessenger.core.crypto.KeyPair? = null

    /** The key the media path should use, once both ends have exchanged ephemerals. */
    fun mediaKey(): ByteArray? = mediaKey

    suspend fun dial(contactId: String): AppResult<Unit> = mutex.withLock {
        if (_session.value != null) return AppResult.Error(AppError.Validation("a call is already in progress"))
        val contact = contactDao.getById(contactId)
            ?: return AppResult.Error(AppError.ContactNotFound)
        val keys = crypto.generateX25519KeyPair()
        ephemeral = keys
        val callId = UUID.randomUUID().toString()
        _session.value = CallSession(
            callId = callId,
            contactId = contactId,
            peerName = contact.displayName,
            outgoing = true,
            state = CallState.OutgoingRinging,
        )
        armTimeout()
        val invited = send(contact, callId, Outgoing(CallSignalType.CALL_SIGNAL_TYPE_INVITE, keys.publicKey))
        activityLogger.record(ActivityKind.CallPlaced)
        if (invited is AppResult.Error) {
            // Nobody to ring: the invite never left. Said now, rather than after a minute of
            // "Calling…" to no one.
            AppLogger.info(TAG, "invite undeliverable; ending call=$callId")
            endQuietly(CallEndReason.Unreachable)
            return invited
        }
        AppLogger.info(TAG, "dialled contact=$contactId call=$callId")
        AppResult.Success(Unit)
    }

    /**
     * Answers the ringing call: the one transition that can lead to an open microphone.
     *
     * The microphone opens here, on this explicit user action, and the addresses the answer carries
     * are where the caller may connect — so the peer never learns where to reach this device for
     * audio until its user has answered.
     */
    suspend fun accept() {
        mutex.withLock { acceptLocked() }
    }

    private suspend fun acceptLocked() {
        val current = _session.value?.takeIf { it.state == CallState.IncomingRinging } ?: return
        val contact = contactDao.getById(current.contactId) ?: return
        val keys = ephemeral ?: crypto.generateX25519KeyPair().also { ephemeral = it }
        val key = mediaKey
        val endpoints = if (key == null) emptyList() else media.accept(key.copyOf(), ::onMediaEvent)
        advance(CallEvent.AcceptedHere)
        send(
            contact,
            current.callId,
            Outgoing(CallSignalType.CALL_SIGNAL_TYPE_ACCEPT, keys.publicKey, endpoints = endpoints),
        )
    }

    suspend fun decline() = end(CallSignalType.CALL_SIGNAL_TYPE_REJECT, CallRejectReason.CALL_REJECT_REASON_DECLINED)

    /**
     * Hangs up. A call that was never answered is cancelled rather than hung up, so the other end
     * can tell "they gave up" from "they hung up on me" — and log it as a missed call, not a call.
     */
    suspend fun hangUp() {
        val ringing = _session.value?.state == CallState.OutgoingRinging
        val type = if (ringing) CallSignalType.CALL_SIGNAL_TYPE_CANCEL else CallSignalType.CALL_SIGNAL_TYPE_HANGUP
        end(type, CallRejectReason.CALL_REJECT_REASON_UNSPECIFIED)
    }

    fun setMuted(muted: Boolean) {
        media.setMuted(muted)
        _session.update { it?.copy(muted = muted) }
    }

    fun setSpeaker(on: Boolean) {
        media.setSpeaker(on)
        _session.update { it?.copy(speakerOn = on) }
    }

    /**
     * What the media path reports: audio up, the path carrying it lost, and a new one bound.
     *
     * A lost path moves a live call to [CallState.Reconnecting] and nothing more — the media path is
     * already opening another, and the state's own deadline ends the call if none binds in time. A
     * loss before audio ever flowed changes nothing either; Connecting has the same deadline. Only a
     * failure no new path could fix, such as the microphone, ends the call here, and ending it
     * signals the peer, who would otherwise wait out its own deadline.
     */
    suspend fun onMediaEvent(event: CallEvent) = mutex.withLock {
        if (event == CallEvent.MediaFailed) {
            _session.value?.let { report(it, CallEndReason.Failed) }
            endLocked(CallSignalType.CALL_SIGNAL_TYPE_HANGUP)
        } else {
            advance(event)
        }
    }

    suspend fun handleSignal(contactId: String, envelope: MessageEnvelope) = mutex.withLock {
        val signal = envelope.callSignal
        val callId = signal.callId.toStringUtf8()
        when (signal.type) {
            CallSignalType.CALL_SIGNAL_TYPE_INVITE -> onInvite(contactId, callId, signal)
            CallSignalType.CALL_SIGNAL_TYPE_RING -> onRing(callId)
            CallSignalType.CALL_SIGNAL_TYPE_ACCEPT -> onAccept(callId, signal)
            CallSignalType.CALL_SIGNAL_TYPE_REJECT,
            CallSignalType.CALL_SIGNAL_TYPE_BUSY,
            CallSignalType.CALL_SIGNAL_TYPE_CANCEL,
            CallSignalType.CALL_SIGNAL_TYPE_HANGUP,
            -> onPeerEnded(callId, signal)
            else -> AppLogger.info(TAG, "ignored call signal type=${signal.type} call=$callId")
        }
    }

    private suspend fun onInvite(contactId: String, callId: String, signal: CallSignal) {
        val contact = contactDao.getById(contactId) ?: return
        val busy = _session.value != null
        if (busy) {
            send(contact, callId, Outgoing(CallSignalType.CALL_SIGNAL_TYPE_BUSY))
            AppLogger.info(TAG, "declined a second call as busy contact=$contactId")
            return
        }
        val keys = crypto.generateX25519KeyPair()
        ephemeral = keys
        deriveMediaKey(keys.privateKey, signal.mediaEphemeralPub.toByteArray())
        _session.value = CallSession(
            callId = callId,
            contactId = contactId,
            peerName = contact.displayName,
            outgoing = false,
            state = CallState.IncomingRinging,
        )
        armTimeout()
        // Tell them this phone is alerting; it is what turns "calling" into "ringing" for them.
        send(contact, callId, Outgoing(CallSignalType.CALL_SIGNAL_TYPE_RING))
        activityLogger.record(ActivityKind.CallReceived)
        AppLogger.info(TAG, "incoming call contact=$contactId call=$callId")
    }

    private fun onRing(callId: String) {
        val current = _session.value ?: return
        if (current.callId != callId) return
        _session.value = current.copy(peerAlerting = true)
    }

    /**
     * They answered. This is where the caller learns where to send audio, and the only place it
     * opens a media connection.
     */
    private suspend fun onAccept(callId: String, signal: CallSignal) {
        val ours = _session.value?.takeIf { it.callId == callId && it.outgoing }
        val keys = ephemeral
        if (ours == null || keys == null) return
        deriveMediaKey(keys.privateKey, signal.mediaEphemeralPub.toByteArray())
        advance(CallEvent.AcceptReceived)
        val key = mediaKey
        val endpoints = signal.mediaEndpointsList.map { MediaEndpoint(it.address, it.relay) }
        val contact = contactDao.getById(ours.contactId)
        if (key != null && contact != null && endpoints.isNotEmpty()) {
            media.connect(endpoints, contact.identityHash, key.copyOf(), ::onMediaEvent)
        } else {
            AppLogger.warn(TAG, "accepted with no usable media address; ending call=$callId")
            endLocked(CallSignalType.CALL_SIGNAL_TYPE_HANGUP)
        }
    }

    private fun onPeerEnded(callId: String, signal: CallSignal) {
        val current = _session.value ?: return
        if (current.callId != callId) return
        if (current.outgoing && current.state == CallState.OutgoingRinging) {
            refusalOf(signal)?.let { report(current, it) }
        }
        advance(CallEvent.EndedByPeer)
        clear()
    }

    /** What a peer's ending of a call that was still ringing means to the caller, if anything. */
    private fun refusalOf(signal: CallSignal): CallEndReason? = when {
        signal.type == CallSignalType.CALL_SIGNAL_TYPE_BUSY ||
            signal.rejectReason == CallRejectReason.CALL_REJECT_REASON_BUSY -> CallEndReason.Busy
        signal.rejectReason == CallRejectReason.CALL_REJECT_REASON_TIMEOUT -> CallEndReason.NoAnswer
        signal.type == CallSignalType.CALL_SIGNAL_TYPE_REJECT -> CallEndReason.Declined
        else -> null
    }

    private fun report(session: CallSession, reason: CallEndReason) {
        _ended.tryEmit(CallEnd(session.callId, session.peerName, reason))
    }

    /** Ends a call nothing was ever sent for, telling the caller why. */
    private fun endQuietly(reason: CallEndReason) {
        _session.value?.let { report(it, reason) }
        advance(CallEvent.EndedHere)
        clear()
    }

    private suspend fun end(type: CallSignalType, reason: CallRejectReason) = mutex.withLock {
        endLocked(type, reason)
    }

    /** The body of [end], callable from anything that already holds [mutex]. */
    private suspend fun endLocked(
        type: CallSignalType,
        reason: CallRejectReason = CallRejectReason.CALL_REJECT_REASON_UNSPECIFIED,
    ) {
        val current = _session.value ?: return
        contactDao.getById(current.contactId)?.let { contact ->
            send(contact, current.callId, Outgoing(type, reason = reason))
        }
        advance(CallEvent.EndedHere)
        clear()
    }

    /**
     * Applies an event, ignoring one that does not fit.
     *
     * Signalling races are ordinary — an accept and a hangup cross on the wire — and the loser has
     * to be dropped rather than allowed to move a call that is already over.
     */
    private fun advance(event: CallEvent) {
        val current = _session.value ?: return
        val next = current.state.next(event)
        if (next == null) {
            AppLogger.info(TAG, "ignored $event in ${current.state}")
            return
        }
        val connectedAt = current.connectedAtMs
            ?: System.currentTimeMillis().takeIf { next == CallState.Active }
        _session.value = current.copy(state = next, connectedAtMs = connectedAt)
        armTimeout()
    }

    /**
     * Gives the state the call just entered its deadline, replacing the last one.
     *
     * Nothing used to end a call that simply stopped: an unanswered call rang for ever, a caller
     * whose process died left the callee ringing, and a media path that connected but never carried
     * a frame held both ends in Connecting. Each waiting state now has a limit, and running out ends
     * the call the way the user would — cancelling a call nobody answered, hanging up one that never
     * came through. [CallState.Active] has none: a call in progress is not waiting for anything.
     */
    private fun armTimeout() {
        timeout?.cancel()
        val armed = _session.value ?: return
        val limit = timeoutOf(armed.state) ?: return
        timeout = scope.launch {
            delay(limit)
            mutex.withLock {
                val now = _session.value
                if (now != null && now.callId == armed.callId && now.state == armed.state) timeOutLocked(now)
            }
        }
    }

    private suspend fun timeOutLocked(session: CallSession) {
        AppLogger.info(TAG, "call timed out in ${session.state} call=${session.callId}")
        val type = when (session.state) {
            CallState.OutgoingRinging -> CallSignalType.CALL_SIGNAL_TYPE_CANCEL
            // The caller's own, shorter limit has normally cancelled by now; this is the backstop
            // for one that went away without saying so, and it tells them in case they are still there.
            CallState.IncomingRinging -> CallSignalType.CALL_SIGNAL_TYPE_REJECT
            else -> CallSignalType.CALL_SIGNAL_TYPE_HANGUP
        }
        contactDao.getById(session.contactId)?.let { contact ->
            send(contact, session.callId, Outgoing(type, reason = CallRejectReason.CALL_REJECT_REASON_TIMEOUT))
        }
        when (session.state) {
            CallState.OutgoingRinging -> report(session, CallEndReason.NoAnswer)
            CallState.Connecting, CallState.Reconnecting -> report(session, CallEndReason.Failed)
            else -> Unit
        }
        advance(CallEvent.TimedOut)
        clear()
    }

    private fun deriveMediaKey(privateKey: ByteArray, peerPublic: ByteArray) {
        if (peerPublic.size != X25519_KEY_SIZE) return
        mediaKey = runCatching {
            crypto.hkdfSha256(
                ikm = crypto.x25519SharedSecret(privateKey, peerPublic),
                salt = ByteArray(0),
                info = MEDIA_KEY_INFO.toByteArray(),
                length = MEDIA_KEY_SIZE,
            )
        }.onFailure { AppLogger.warn(TAG, "media key derivation failed: ${it.message}") }.getOrNull()
    }

    private fun clear() {
        timeout?.cancel()
        timeout = null
        // Only that a call ended. The peer is deliberately absent — see ActivityLogEntity.
        if (_session.value != null) activityLogger.record(ActivityKind.CallEnded)
        // Before the key is zeroed: the media path is holding a reference to it.
        media.stop()
        mediaKey?.fill(0)
        mediaKey = null
        ephemeral?.privateKey?.fill(0)
        ephemeral = null
        _session.value = null
    }

    /** One outgoing signal's contents, bundled so [send] keeps a readable signature. */
    private class Outgoing(
        val type: CallSignalType,
        val ephemeralPublic: ByteArray = ByteArray(0),
        val reason: CallRejectReason = CallRejectReason.CALL_REJECT_REASON_UNSPECIFIED,
        val endpoints: List<MediaEndpoint> = emptyList(),
    )

    private suspend fun send(contact: ContactEntity, callId: String, outgoing: Outgoing): AppResult<Unit> {
        val self = selfIdentityCache.get() ?: return AppResult.Error(AppError.NotFound("no identity to call from"))
        val signal = CallSignal.newBuilder()
            .setCallId(ByteString.copyFromUtf8(callId))
            .setType(outgoing.type)
            .setRejectReason(outgoing.reason)
        if (outgoing.ephemeralPublic.isNotEmpty()) {
            signal.mediaEphemeralPub = ByteString.copyFrom(outgoing.ephemeralPublic)
        }
        outgoing.endpoints.forEach { endpoint ->
            signal.addMediaEndpoints(CallEndpoint.newBuilder().setAddress(endpoint.address).setRelay(endpoint.relay))
        }
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(
                ByteString.copyFromUtf8("call-$callId-${outgoing.type.number}-${System.currentTimeMillis()}"),
            )
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setCallSignal(signal)
            .build()
        return messaging.send(contact.id, self, peerOf(contact), envelope)
    }

    private fun peerOf(contact: ContactEntity) = PeerIdentity(
        identityHash = contact.identityHash,
        ed25519PublicKey = contact.ed25519Public,
        x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
    )

    private companion object {
        const val TAG = "Call"
        const val X25519_KEY_SIZE = 32
        const val MEDIA_KEY_SIZE = 32

        /** How long a call rings unanswered before the caller gives up, as a phone would. */
        const val RING_TIMEOUT_MS = 60_000L

        /** Longer than [RING_TIMEOUT_MS], so the caller's cancel normally arrives first. */
        const val INCOMING_RING_TIMEOUT_MS = 75_000L

        /**
         * Answered but no audio yet, or audio lost and not yet back: long enough for several rounds
         * of dialling every advertised path, relay included.
         */
        const val CONNECT_TIMEOUT_MS = 30_000L

        fun timeoutOf(state: CallState): Long? = when (state) {
            CallState.OutgoingRinging -> RING_TIMEOUT_MS
            CallState.IncomingRinging -> INCOMING_RING_TIMEOUT_MS
            CallState.Connecting, CallState.Reconnecting -> CONNECT_TIMEOUT_MS
            CallState.Idle, CallState.Active, CallState.Ending -> null
        }

        /** Domain separation, so this key can never collide with a messaging or backup key. */
        const val MEDIA_KEY_INFO = "vmessenger-call-media-v1"
    }
}
