package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.repository.findByIdentityHash
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.ContactRequestRepository
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.domain.model.ContactRelationshipStatus as DomainRelationshipStatus

@Singleton
class ContactRequestHandler @Inject constructor(
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val contactRequestNotifier: ContactRequestNotifier,
    private val contactRequestService: ContactRequestService,
    private val contactDao: ContactDao,
) {
    @Suppress("ReturnCount")
    suspend fun handleRequest(envelope: MessageEnvelope, peer: PeerIdentity?) {
        val request = envelope.contactRequest
        val requestId = request.requestId.toStringUtf8()
        if (requestId.isBlank()) return
        val identityPub = request.requesterIdentityPub.toByteArray()
        val identityHash = UserHashEncoder.identityHashFromPublicKey(identityPub)
        val domainRequest = ContactRequest(
            requestId = requestId,
            requesterIdentityHash = identityHash,
            requesterUserHash = request.requesterUserHash,
            requesterDisplayName = request.requesterDisplayName.ifBlank { request.requesterUserHash },
            requesterEd25519PublicKey = identityPub,
            requesterX25519StaticPublicKey = peer?.x25519StaticPublicKey,
            receivedAtUnixMs = envelope.sentAtUnixMs,
        )
        // Requesters retry until they hear back. If we already approved this peer
        // (their side missed the accept), or we added them ourselves and are
        // waiting on them (mutual add), answer immediately instead of prompting.
        val existing = contactDao.findByIdentityHash(identityHash)
        val autoAcceptable = existing != null && (
            existing.relationshipStatus == ContactRelationshipStatus.APPROVED ||
                existing.relationshipStatus == ContactRelationshipStatus.PENDING_OUT
            )
        if (autoAcceptable) {
            approveRequestSilently(domainRequest)
            return
        }
        // The user rejected this requester repeatedly — stop bothering them and
        // just decline further requests silently (without re-counting).
        if (contactRequestRepository.rejectCountOf(requestId) >= MAX_REJECTS_BEFORE_SILENT) {
            sendRejectResponse(domainRequest)
            AppLogger.info("Contact", "silently declined repeat request from ${request.requesterUserHash}")
            return
        }
        contactRequestRepository.saveRequest(domainRequest)
        contactRequestNotifier.notify(domainRequest)
        AppLogger.info("Contact", "incoming contact request from ${request.requesterUserHash}")
    }

    private suspend fun approveRequestSilently(request: ContactRequest) {
        contactRequestRepository.saveRequest(request)
        approveRequest(request)
        AppLogger.info("Contact", "auto-accepted request from already-approved ${request.requesterUserHash}")
    }

    suspend fun handleResponse(contactId: String, envelope: MessageEnvelope) {
        val response = envelope.contactResponse
        val requestId = response.requestId.toStringUtf8()
        when (response.type) {
            ContactResponseType.CONTACT_RESPONSE_ACCEPT -> {
                contactRepository.getContact(contactId)?.let { contact ->
                    contactRepository.updateRelationshipStatus(
                        contact.id,
                        DomainRelationshipStatus.APPROVED,
                    )
                    applyResponderProfile(contact.id, response)
                }
                AppLogger.info("Contact", "contact request accepted requestId=$requestId")
            }
            ContactResponseType.CONTACT_RESPONSE_REJECT -> {
                contactRepository.getContact(contactId)?.let { contact ->
                    contactRepository.updateRelationshipStatus(
                        contact.id,
                        DomainRelationshipStatus.REJECTED,
                    )
                }
                AppLogger.info("Contact", "contact request rejected requestId=$requestId")
            }
            else -> Unit
        }
    }

    suspend fun approveRequest(request: ContactRequest) {
        val contact = contactRequestRepository.acceptRequest(request.requestId)
        if (contact is ir.vmessenger.core.common.AppResult.Success) {
            val peer = PeerIdentity(
                identityHash = request.requesterIdentityHash,
                ed25519PublicKey = request.requesterEd25519PublicKey,
                x25519StaticPublicKey = request.requesterX25519StaticPublicKey ?: ByteArray(32),
            )
            contactRequestService.sendResponse(
                contactId = contact.data.id,
                peer = peer,
                requestId = request.requestId,
                accepted = true,
            )
        }
    }

    private suspend fun applyResponderProfile(
        contactId: String,
        response: ir.vmessenger.core.proto.app.v1.ContactResponse,
    ) {
        val entity = contactDao.getById(contactId) ?: return
        val responderPub = response.responderIdentityPub.toByteArray()
        val hasResponderIdentity = responderPub.size == 32 &&
            !IdentityHashMatcher.isPlaceholderPublicKey(responderPub)
        val fullHash = if (hasResponderIdentity) {
            UserHashEncoder.identityHashFromPublicKey(responderPub)
        } else {
            null
        }
        val newUserHash = response.responderUserHash.ifBlank { entity.userHash }
        // Only replace default names (the raw hash used at add time); never a user alias.
        val hasCustomAlias = entity.displayName.isNotBlank() &&
            entity.displayName != entity.userHash &&
            entity.displayName != newUserHash
        val newDisplayName = when {
            hasCustomAlias -> entity.displayName
            response.responderDisplayName.isNotBlank() -> response.responderDisplayName
            else -> entity.displayName
        }
        contactDao.update(
            entity.copy(
                relationshipStatus = ContactRelationshipStatus.APPROVED,
                identityHash = fullHash ?: entity.identityHash,
                ed25519Public = if (hasResponderIdentity) responderPub else entity.ed25519Public,
                userHash = newUserHash,
                displayName = newDisplayName,
            ),
        )
    }

    suspend fun rejectRequest(request: ContactRequest) {
        contactRequestRepository.rejectRequest(request.requestId)
        sendRejectResponse(request)
    }

    private suspend fun sendRejectResponse(request: ContactRequest) {
        val peer = PeerIdentity(
            identityHash = request.requesterIdentityHash,
            ed25519PublicKey = request.requesterEd25519PublicKey,
            x25519StaticPublicKey = request.requesterX25519StaticPublicKey ?: ByteArray(32),
        )
        val strangerId = strangerContactId(request.requesterIdentityHash)
        contactRequestService.sendResponse(
            contactId = strangerId,
            peer = peer,
            requestId = request.requestId,
            accepted = false,
        )
    }

    companion object {
        private const val MAX_REJECTS_BEFORE_SILENT = 2

        fun strangerContactId(identityHash: ByteArray): String =
            "stranger:" + identityHash.joinToString("") { "%02x".format(it) }
    }
}
