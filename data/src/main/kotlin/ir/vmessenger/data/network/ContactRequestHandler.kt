package ir.vmessenger.data.network

import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.domain.model.ContactRelationshipStatus as DomainRelationshipStatus
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.ContactRequestRepository
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRequestHandler @Inject constructor(
    private val contactRequestRepository: ContactRequestRepository,
    private val contactRepository: ContactRepository,
    private val contactRequestNotifier: ContactRequestNotifier,
    private val contactRequestService: ContactRequestService,
    private val contactDao: ContactDao,
) {
    suspend fun handleRequest(contactId: String, envelope: MessageEnvelope, peer: PeerIdentity?) {
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
        contactRequestRepository.saveRequest(domainRequest)
        contactRequestNotifier.notify(domainRequest)
        AppLogger.info("Contact", "incoming contact request from ${request.requesterUserHash}")
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
                    contactDao.update(
                        contactDao.getById(contact.id)!!.copy(
                            relationshipStatus = ContactRelationshipStatus.APPROVED,
                        ),
                    )
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

    suspend fun rejectRequest(request: ContactRequest) {
        contactRequestRepository.rejectRequest(request.requestId)
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
        fun strangerContactId(identityHash: ByteArray): String =
            "stranger:" + identityHash.joinToString("") { "%02x".format(it) }
    }
}
