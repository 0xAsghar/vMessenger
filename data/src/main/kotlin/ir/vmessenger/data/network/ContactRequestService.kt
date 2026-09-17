package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.proto.app.v1.ContactRequest
import ir.vmessenger.core.proto.app.v1.ContactResponse
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.ContactRequestSender
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRequestService @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val selfIdentityCache: SelfIdentityCache,
    private val messagingService: MessagingPort,
    private val retryBudget: ContactRequestRetryBudget,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : ContactRequestSender {
    /** Outlives the screen that added the contact, which is usually gone before the send settles. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler("Contact"))

    /** The user's own (re)send: also gives the automatic retry worker a fresh budget for this contact. */
    override suspend fun sendRequest(contact: Contact): AppResult<Unit> {
        retryBudget.reset(contact.id)
        return deliverRequest(contact)
    }

    override fun sendRequestInBackground(contact: Contact) {
        scope.launch {
            val result = sendRequest(contact)
            if (result is AppResult.Error) {
                AppLogger.info("Contact", "first request to ${contact.userHash} not delivered; retry worker takes over")
            }
        }
    }

    /**
     * One request send without touching the retry budget (used by the retry worker itself).
     * [forceReconnect] as in [MessagingPort.send].
     */
    suspend fun deliverRequest(contact: Contact, forceReconnect: Boolean = false): AppResult<Unit> {
        val (identity, self) = selfOrNull() ?: return identityMissing()
        val peer = PeerIdentity(
            identityHash = contact.identityHash,
            ed25519PublicKey = contact.ed25519PublicKey,
            x25519StaticPublicKey = contact.x25519StaticPublicKey ?: ByteArray(X25519_KEY_SIZE),
        )
        // Deterministic per (requester, target) so retries upsert the same row on
        // the receiver instead of piling up duplicate pending requests.
        val requestId = deterministicRequestId(identity.identityHash, contact.identityHash)
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("contact-req-$requestId"))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setContactRequest(
                ContactRequest.newBuilder()
                    .setRequesterIdentityPub(ByteString.copyFrom(identity.ed25519PublicKey))
                    .setRequesterUserHash(identity.userHash)
                    .setRequesterDisplayName(
                        identity.displayName.ifBlank { identity.userHash },
                    )
                    .setRequestId(ByteString.copyFromUtf8(requestId)),
            )
            .build()
        return when (val result = messagingService.send(contact.id, self, peer, envelope, forceReconnect)) {
            is AppResult.Success -> {
                AppLogger.info("Contact", "contact request sent to ${contact.userHash}")
                AppResult.Success(Unit)
            }
            is AppResult.Error -> result
        }
    }

    suspend fun sendResponse(
        contactId: String,
        peer: PeerIdentity,
        requestId: String,
        accepted: Boolean,
    ): AppResult<Unit> = sendResponse(
        contactId = contactId,
        peer = peer,
        requestId = requestId,
        type = if (accepted) {
            ContactResponseType.CONTACT_RESPONSE_ACCEPT
        } else {
            ContactResponseType.CONTACT_RESPONSE_REJECT
        },
    )

    /**
     * Sends one contact response of [type]. `REVOKE` tells a peer we deleted
     * them; its request id is the deterministic id of *their* request to us so
     * the receiver can validate it like any other response.
     */
    suspend fun sendResponse(
        contactId: String,
        peer: PeerIdentity,
        requestId: String,
        type: ContactResponseType,
    ): AppResult<Unit> {
        val (identity, self) = selfOrNull() ?: return identityMissing()
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("contact-resp-$requestId"))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setContactResponse(
                ContactResponse.newBuilder()
                    .setRequestId(ByteString.copyFromUtf8(requestId))
                    .setType(type)
                    .setResponderIdentityPub(ByteString.copyFrom(identity.ed25519PublicKey))
                    .setResponderUserHash(identity.userHash)
                    .setResponderDisplayName(identity.displayName.ifBlank { identity.userHash }),
            )
            .build()
        return messagingService.send(contactId, self, peer, envelope)
    }

    /** Our display identity plus the cached key material, or null while either is missing. */
    private suspend fun selfOrNull(): Pair<Identity, PeerIdentity>? =
        identityRepository.getIdentity()?.let { identity ->
            selfIdentityCache.get()?.let { self -> identity to self }
        }

    private fun identityMissing(): AppResult<Unit> = AppResult.Error(AppError.NotFound(IDENTITY_MISSING_MESSAGE))

    companion object {
        private const val X25519_KEY_SIZE = 32
        private const val REQUEST_ID_HEX_CHARS = 32
        private const val IDENTITY_MISSING_MESSAGE = "هویت یافت نشد"

        /**
         * Request id derived from the (requester, target) identity hashes, so
         * retries upsert one row on the receiver and a response can be checked
         * against the request we actually sent.
         */
        fun deterministicRequestId(requesterHash: ByteArray, targetHash: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(requesterHash + targetHash)
            return "cr-" + digest.joinToString("") { "%02x".format(it) }.take(REQUEST_ID_HEX_CHARS)
        }
    }
}
