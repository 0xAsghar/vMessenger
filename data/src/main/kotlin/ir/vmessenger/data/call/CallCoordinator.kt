package ir.vmessenger.data.call

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.proto.app.v1.CallRejectReason
import ir.vmessenger.core.proto.app.v1.CallSignal
import ir.vmessenger.core.proto.app.v1.CallSignalType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.network.MessagingPort
import ir.vmessenger.data.network.SelfIdentityCache
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
// state machine from the sends that must accompany each transition.
@Suppress("TooManyFunctions")
class CallCoordinator @Inject constructor(
    private val contactDao: ContactDao,
    private val selfIdentityCache: SelfIdentityCache,
    private val messaging: MessagingPort,
    private val crypto: CryptoEngine,
) {
    private val mutex = Mutex()
    private val _session = MutableStateFlow<CallSession?>(null)

    val session: StateFlow<CallSession?> = _session.asStateFlow()

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
        send(contact, callId, CallSignalType.CALL_SIGNAL_TYPE_INVITE, keys.publicKey)
        AppLogger.info(TAG, "dialled contact=$contactId call=$callId")
        AppResult.Success(Unit)
    }

    /** Answers the ringing call: the one transition that can lead to an open microphone. */
    suspend fun accept() = mutex.withLock {
        val current = _session.value ?: return
        if (current.state != CallState.IncomingRinging) return
        val contact = contactDao.getById(current.contactId) ?: return
        val keys = ephemeral ?: crypto.generateX25519KeyPair().also { ephemeral = it }
        advance(CallEvent.AcceptedHere)
        send(contact, current.callId, CallSignalType.CALL_SIGNAL_TYPE_ACCEPT, keys.publicKey)
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
        _session.update { it?.copy(muted = muted) }
    }

    /** Called by the media path when its first frame lands, and when it drops or returns. */
    suspend fun onMediaEvent(event: CallEvent) = mutex.withLock { advance(event) }

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
            -> onPeerEnded(callId)
            else -> AppLogger.info(TAG, "ignored call signal type=${signal.type} call=$callId")
        }
    }

    private suspend fun onInvite(contactId: String, callId: String, signal: CallSignal) {
        val contact = contactDao.getById(contactId) ?: return
        val busy = _session.value != null
        if (busy) {
            send(contact, callId, CallSignalType.CALL_SIGNAL_TYPE_BUSY, ByteArray(0))
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
        // Tell them this phone is alerting; it is what turns "calling" into "ringing" for them.
        send(contact, callId, CallSignalType.CALL_SIGNAL_TYPE_RING, ByteArray(0))
        AppLogger.info(TAG, "incoming call contact=$contactId call=$callId")
    }

    private fun onRing(callId: String) {
        val current = _session.value ?: return
        if (current.callId != callId) return
        _session.value = current.copy(peerAlerting = true)
    }

    private fun onAccept(callId: String, signal: CallSignal) {
        val ours = _session.value?.takeIf { it.callId == callId && it.outgoing }
        val keys = ephemeral
        if (ours == null || keys == null) return
        deriveMediaKey(keys.privateKey, signal.mediaEphemeralPub.toByteArray())
        advance(CallEvent.AcceptReceived)
    }

    private fun onPeerEnded(callId: String) {
        if (_session.value?.callId != callId) return
        advance(CallEvent.EndedByPeer)
        clear()
    }

    private suspend fun end(type: CallSignalType, reason: CallRejectReason) = mutex.withLock {
        val current = _session.value ?: return
        contactDao.getById(current.contactId)?.let { contact ->
            send(contact, current.callId, type, ByteArray(0), reason)
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
        _session.value = current.copy(state = next)
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
        mediaKey?.fill(0)
        mediaKey = null
        ephemeral?.privateKey?.fill(0)
        ephemeral = null
        _session.value = null
    }

    private suspend fun send(
        contact: ContactEntity,
        callId: String,
        type: CallSignalType,
        ephemeralPublic: ByteArray,
        reason: CallRejectReason = CallRejectReason.CALL_REJECT_REASON_UNSPECIFIED,
    ) {
        val self = selfIdentityCache.get() ?: return
        val signal = CallSignal.newBuilder()
            .setCallId(ByteString.copyFromUtf8(callId))
            .setType(type)
            .setRejectReason(reason)
        if (ephemeralPublic.isNotEmpty()) {
            signal.mediaEphemeralPub = ByteString.copyFrom(ephemeralPublic)
        }
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("call-$callId-${type.number}-${System.currentTimeMillis()}"))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setCallSignal(signal)
            .build()
        messaging.send(contact.id, self, peerOf(contact), envelope)
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

        /** Domain separation, so this key can never collide with a messaging or backup key. */
        const val MEDIA_KEY_INFO = "vmessenger-call-media-v1"
    }
}
