package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.proto.app.v1.ContactRequest
import ir.vmessenger.core.proto.app.v1.ContactResponse
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.ContactRequestSender

@Singleton
class ContactRequestService @Inject constructor(
    private val identityRepository: IdentityRepository,
    private val messagingService: MessagingService,
) : ContactRequestSender {
    override suspend fun sendRequest(contact: Contact): AppResult<Unit> {
        val identity = identityRepository.getIdentity()
            ?: return AppResult.Error(ir.vmessenger.core.common.AppError.NotFound("هویت یافت نشد"))
        val self = selfPeer(identity)
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
        return when (val result = messagingService.send(contact.id, self, peer, envelope)) {
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
    ): AppResult<Unit> {
        val identity = identityRepository.getIdentity()
            ?: return AppResult.Error(ir.vmessenger.core.common.AppError.NotFound("هویت یافت نشد"))
        val self = selfPeer(identity)
        val type = if (accepted) {
            ContactResponseType.CONTACT_RESPONSE_ACCEPT
        } else {
            ContactResponseType.CONTACT_RESPONSE_REJECT
        }
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

    private suspend fun selfPeer(identity: Identity): PeerIdentity = PeerIdentity(
        identityHash = identity.identityHash,
        ed25519PublicKey = identity.ed25519PublicKey,
        x25519StaticPublicKey = identity.x25519StaticPublicKey,
        ed25519PrivateKey = identityRepository.getEd25519PrivateKey(),
        x25519StaticPrivateKey = identityRepository.getX25519StaticPrivateKey(),
    )

    companion object {
        private const val X25519_KEY_SIZE = 32
        private const val REQUEST_ID_HEX_CHARS = 32

        fun deterministicRequestId(requesterHash: ByteArray, targetHash: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(requesterHash + targetHash)
            return "cr-" + digest.joinToString("") { "%02x".format(it) }.take(REQUEST_ID_HEX_CHARS)
        }
    }
}
