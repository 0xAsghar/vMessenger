package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.proto.app.v1.ContactResponse
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.repository.findByIdentityHash
import ir.vmessenger.data.repository.hasPinnedStaticKey
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.ContactRequestRepository
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.domain.model.ContactRelationshipStatus as DomainRelationshipStatus

/**
 * Inbound side of the contact-request protocol.
 *
 * Identity material is only ever taken from the authenticated session peer:
 * the request/response payloads carry display strings, never keys we trust.
 */
@Singleton
class ContactRequestHandler @Inject constructor(
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val contactRequestNotifier: ContactRequestNotifier,
    private val contactRequestService: ContactRequestService,
    private val contactDao: ContactDao,
    private val identityRepository: IdentityRepository,
) {
    @Suppress("ReturnCount") // each early exit is a distinct, logged policy outcome
    suspend fun handleRequest(envelope: MessageEnvelope, peer: PeerIdentity?) {
        val request = envelope.contactRequest
        val requestId = request.requestId.toStringUtf8()
        if (requestId.isBlank()) return
        val payloadPub = request.requesterIdentityPub.toByteArray()
        // The handshake proved which key the requester holds; a payload naming a
        // different identity would let a stranger file requests as someone else.
        if (peer != null && payloadPub.isNotEmpty() && !payloadPub.contentEquals(peer.ed25519PublicKey)) {
            AppLogger.warn("Contact", "contact request ignored: payload identity differs from session peer")
            return
        }
        val identityPub = peer?.ed25519PublicKey ?: payloadPub
        val identityHash = peer?.identityHash ?: UserHashEncoder.identityHashFromPublicKey(identityPub)
        // The id is deterministic over (requester, us). Any other id would let a
        // peer overwrite another requester's pending row or dodge the reject cap.
        if (requestId !in acceptedRequestIds(identityHash)) {
            AppLogger.warn("Contact", "contact request ignored: unexpected request id")
            return
        }
        // The user hash shown on the approval card is derived from the proven
        // identity; the payload's string could name any contact the user trusts.
        val userHash = UserHashEncoder.encode(identityHash)
        val domainRequest = ContactRequest(
            requestId = requestId,
            requesterIdentityHash = identityHash,
            requesterUserHash = userHash,
            requesterDisplayName = request.requesterDisplayName.ifBlank { userHash },
            requesterEd25519PublicKey = identityPub,
            requesterX25519StaticPublicKey = peer?.x25519StaticPublicKey,
            receivedAtUnixMs = System.currentTimeMillis(),
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
            AppLogger.info("Contact", "silently declined repeat request from $userHash")
            return
        }
        contactRequestRepository.saveRequest(domainRequest)
        contactRequestNotifier.notify(domainRequest)
        AppLogger.info("Contact", "incoming contact request from $userHash")
    }

    /**
     * Request ids a requester may file with us: the deterministic id over our
     * full hash, or over our routing prefix when they added us by user hash.
     * Mirror of [expectedRequestIds] for the inbound direction.
     */
    private suspend fun acceptedRequestIds(requesterHash: ByteArray): Set<String> {
        val self = identityRepository.getIdentity() ?: return emptySet()
        return listOf(self.identityHash, IdentityHashMatcher.routingHash(self.identityHash))
            .map { ContactRequestService.deterministicRequestId(requesterHash, it) }
            .toSet()
    }

    private suspend fun approveRequestSilently(request: ContactRequest) {
        contactRequestRepository.saveRequest(request)
        approveRequest(request)
        AppLogger.info("Contact", "auto-accepted request from already-approved ${request.requesterUserHash}")
    }

    /**
     * Applies a response to a request *we* sent. Only a contact we are waiting
     * on (PENDING_OUT, or APPROVED for the mutual-add echo) can answer, and the
     * request id must be the one we derived for this contact — anything else is
     * a stale or forged response and is ignored.
     */
    suspend fun handleResponse(contactId: String, envelope: MessageEnvelope, sessionPeer: PeerIdentity?) {
        val response = envelope.contactResponse
        val requestId = response.requestId.toStringUtf8()
        val contact = validatedResponder(contactId, requestId, sessionPeer) ?: return
        when (response.type) {
            ContactResponseType.CONTACT_RESPONSE_ACCEPT -> {
                applyResponderProfile(contact, response, sessionPeer)
                AppLogger.info("Contact", "contact request accepted contact=$contactId")
            }
            ContactResponseType.CONTACT_RESPONSE_REJECT -> {
                contactRepository.updateRelationshipStatus(contact.id, DomainRelationshipStatus.REJECTED)
                AppLogger.info("Contact", "contact request rejected contact=$contactId")
            }
            ContactResponseType.CONTACT_RESPONSE_REVOKE -> {
                contactRepository.updateRelationshipStatus(contact.id, DomainRelationshipStatus.REJECTED)
                AppLogger.info("Contact", "contact revoked by peer contact=$contactId")
            }
            else -> Unit
        }
    }

    private suspend fun validatedResponder(
        contactId: String,
        requestId: String,
        sessionPeer: PeerIdentity?,
    ): ContactEntity? {
        val contact = contactDao.getById(contactId)
        if (contact == null) {
            AppLogger.warn("Contact", "contact response ignored: no PENDING_OUT contact=$contactId")
            return null
        }
        val awaited = contact.relationshipStatus == ContactRelationshipStatus.PENDING_OUT ||
            contact.relationshipStatus == ContactRelationshipStatus.APPROVED
        val peerMatches = sessionPeer == null ||
            IdentityHashMatcher.matches(contact.identityHash, sessionPeer.identityHash)
        val reason = when {
            !awaited -> "no PENDING_OUT"
            !peerMatches -> "session peer is not this contact"
            requestId !in expectedRequestIds(contact, sessionPeer) -> "unexpected request id"
            else -> null
        }
        if (reason != null) {
            AppLogger.warn("Contact", "contact response ignored: $reason contact=$contactId")
        }
        return if (reason == null) contact else null
    }

    /**
     * Request ids we may have sent this contact. A hash-added contact is stored
     * with the 16-byte routing prefix until the first handshake fills in the
     * full hash, so both forms (for the stored and the authenticated hash) are
     * accepted; nothing else is.
     */
    private suspend fun expectedRequestIds(contact: ContactEntity, sessionPeer: PeerIdentity?): Set<String> {
        val self = identityRepository.getIdentity() ?: return emptySet()
        val targets = listOfNotNull(contact.identityHash, sessionPeer?.identityHash)
        return targets.flatMap { listOf(it, IdentityHashMatcher.routingHash(it)) }
            .map { ContactRequestService.deterministicRequestId(self.identityHash, it) }
            .toSet()
    }

    suspend fun approveRequest(request: ContactRequest) {
        val contact = contactRequestRepository.acceptRequest(request.requestId)
        if (contact is AppResult.Success) {
            val peer = PeerIdentity(
                identityHash = request.requesterIdentityHash,
                ed25519PublicKey = request.requesterEd25519PublicKey,
                x25519StaticPublicKey = request.requesterX25519StaticPublicKey ?: ByteArray(X25519_KEY_SIZE),
            )
            contactRequestService.sendResponse(
                contactId = contact.data.id,
                peer = peer,
                requestId = request.requestId,
                accepted = true,
            )
        }
    }

    /**
     * Marks the contact approved. Keys come only from the authenticated
     * [sessionPeer] (the payload's `responder_identity_pub` is ignored); only
     * the display name is taken from the payload, and never over a user alias.
     */
    private suspend fun applyResponderProfile(
        entity: ContactEntity,
        response: ContactResponse,
        sessionPeer: PeerIdentity?,
    ) {
        val learnStatic = sessionPeer != null &&
            !entity.hasPinnedStaticKey() &&
            !IdentityHashMatcher.isPlaceholderPublicKey(sessionPeer.x25519StaticPublicKey)
        val newUserHash = sessionPeer?.let { UserHashEncoder.encode(it.identityHash) } ?: entity.userHash
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
                identityHash = sessionPeer?.identityHash ?: entity.identityHash,
                ed25519Public = sessionPeer?.ed25519PublicKey ?: entity.ed25519Public,
                x25519StaticPublic = if (learnStatic) sessionPeer?.x25519StaticPublicKey else entity.x25519StaticPublic,
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
            x25519StaticPublicKey = request.requesterX25519StaticPublicKey ?: ByteArray(X25519_KEY_SIZE),
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
        private const val X25519_KEY_SIZE = 32

        fun strangerContactId(identityHash: ByteArray): String =
            "stranger:" + identityHash.joinToString("") { "%02x".format(it) }
    }
}
