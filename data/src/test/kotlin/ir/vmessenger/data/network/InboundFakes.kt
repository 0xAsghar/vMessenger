package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.database.dao.OutboxDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.OutboxEntity
import ir.vmessenger.core.proto.app.v1.ChatMessage
import ir.vmessenger.core.proto.app.v1.ContactResponse
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.NetworkNodeList
import ir.vmessenger.core.proto.app.v1.Receipt
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.data.repository.FakeIdentityRepository
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRequest
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.repository.ContactRepository
import ir.vmessenger.domain.repository.ContactRequestRepository
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import ir.vmessenger.core.proto.app.v1.ContactRequest as ProtoContactRequest
import ir.vmessenger.domain.model.ContactRelationshipStatus as DomainRelationshipStatus

/** Records what the inbound handlers send back to peers; nothing leaves the process. */
class FakeMessagingPort : MessagingPort {
    /** Envelopes that went through the full resolve+dial path. */
    val sent = mutableListOf<Pair<String, MessageEnvelope>>()

    /** Envelopes written on an already-open outbound session (only when [existingSessionContacts] has the contact). */
    val sentOnExistingSession = mutableListOf<Pair<String, MessageEnvelope>>()

    /** Contacts that currently have an open outbound session. */
    val existingSessionContacts = mutableSetOf<String>()

    /** When set, [send] fails with this error instead of succeeding. */
    var sendError: AppError? = null

    override val incoming: Flow<IncomingEnvelope> = MutableSharedFlow()

    /** The sink the collector installed, so a test can push envelopes through the live path. */
    var installedSink: (suspend (IncomingEnvelope) -> Unit)? = null
        private set

    override fun setIncomingSink(sink: (suspend (IncomingEnvelope) -> Unit)?) {
        installedSink = sink
    }

    override suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelope: MessageEnvelope,
    ): AppResult<Unit> {
        sendError?.let { return AppResult.Error(it) }
        sent += contactId to envelope
        return AppResult.Success(Unit)
    }

    override suspend fun sendOnExistingSession(contactId: String, envelope: MessageEnvelope): Boolean {
        if (contactId !in existingSessionContacts) return false
        sentOnExistingSession += contactId to envelope
        return true
    }

    fun receiptsFor(messageId: String): List<MessageEnvelope> =
        (sent + sentOnExistingSession).map { it.second }
            .filter { it.hasReceipt() && it.receipt.refMessageId.toStringUtf8() == messageId }
}

class FakeInboundRoutes : InboundRoutes {
    val locations = mutableListOf<String>()
    val infrastructure = mutableListOf<MessageEnvelope>()

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun attachmentInfo(contactId: String, envelope: MessageEnvelope): Boolean = false

    override suspend fun attachmentChunk(contactId: String, envelope: MessageEnvelope): CompletedAttachment? = null

    override suspend fun location(contactId: String, envelope: MessageEnvelope) {
        locations += contactId
    }

    override suspend fun control(contactId: String, envelope: MessageEnvelope) = Unit

    override suspend fun infrastructure(incoming: IncomingEnvelope): Boolean {
        infrastructure += incoming.envelope
        return true
    }
}

class FakeIncomingMessageNotifier : IncomingMessageNotifier {
    val shown = mutableListOf<Triple<String, String, String>>()

    override suspend fun notify(senderName: String, preview: String, conversationId: String) {
        shown += Triple(senderName, preview, conversationId)
    }
}

class FakeOutboxDao : OutboxDao {
    val items = mutableListOf<OutboxEntity>()

    override suspend fun enqueue(item: OutboxEntity) {
        items.removeAll { it.messageId == item.messageId }
        items += item
    }

    override suspend fun due(now: Long): List<OutboxEntity> = items.filter { it.nextAttemptUnixMs <= now }

    override suspend fun remove(messageId: String) {
        items.removeAll { it.messageId == messageId }
    }

    override suspend fun removeByConversation(cid: String) {
        items.removeAll { it.conversationId == cid }
    }

    override suspend fun resetBackoff() {
        items.replaceAll { it.copy(nextAttemptUnixMs = 0) }
    }

    override suspend fun update(item: OutboxEntity) {
        items.replaceAll { if (it.messageId == item.messageId) item else it }
    }
}

/** Only the relationship-status path is exercised by the handler tests; everything else is inert. */
@Suppress("TooManyFunctions") // mirrors the full ContactRepository contract
class FakeContactRepository(private val contactDao: ir.vmessenger.core.database.dao.ContactDao) : ContactRepository {
    override fun observeContacts(): Flow<List<Contact>> = flowOf(emptyList())

    override suspend fun getContact(id: String): Contact? = contactDao.getById(id)?.toDomain()

    override suspend fun getContactByIdentityHash(identityHash: ByteArray): Contact? =
        contactDao.getByIdentityHash(identityHash)?.toDomain()

    override suspend fun addContactByDescriptor(descriptorBytes: ByteArray, alias: String?): AppResult<Contact> =
        AppResult.Error(AppError.Validation("unsupported in fake"))

    override suspend fun addContactByUserHash(userHash: String, alias: String?): AppResult<Contact> =
        AppResult.Error(AppError.Validation("unsupported in fake"))

    override suspend fun addApprovedContact(
        identityHash: ByteArray,
        ed25519Public: ByteArray,
        x25519StaticPublic: ByteArray?,
        userHash: String,
        displayName: String,
    ): AppResult<Contact> = AppResult.Error(AppError.Validation("unsupported in fake"))

    override suspend fun updateRelationshipStatus(id: String, status: DomainRelationshipStatus) {
        val entity = contactDao.getById(id) ?: return
        contactDao.update(entity.copy(relationshipStatus = ContactRelationshipStatus.valueOf(status.name)))
    }

    override suspend fun updateContactAlias(id: String, alias: String) = Unit

    override suspend fun blockContact(id: String, blocked: Boolean) = Unit

    override suspend fun deleteContact(id: String) = contactDao.deleteById(id)

    override suspend fun acceptKeyChange(id: String): AppResult<Unit> = AppResult.Success(Unit)

    private fun ContactEntity.toDomain() = Contact(
        id = id,
        identityHash = identityHash,
        ed25519PublicKey = ed25519Public,
        x25519StaticPublicKey = x25519StaticPublic,
        userHash = userHash,
        displayName = displayName,
        verified = verified,
        blocked = blocked,
        relationshipStatus = DomainRelationshipStatus.valueOf(relationshipStatus.name),
        createdAtUnixMs = createdAtUnixMs,
        lastSeenUnixMs = lastSeenUnixMs,
    )
}

class FakeContactRequestRepository : ContactRequestRepository {
    val saved = mutableListOf<ContactRequest>()

    override fun observePendingRequests(): Flow<List<ContactRequest>> = flowOf(saved.toList())

    override suspend fun saveRequest(request: ContactRequest) {
        saved.removeAll { it.requestId == request.requestId }
        saved += request
    }

    override suspend fun getRequest(requestId: String): ContactRequest? =
        saved.firstOrNull { it.requestId == requestId }

    override suspend fun acceptRequest(requestId: String): AppResult<Contact> =
        AppResult.Error(AppError.NotFound("unsupported in fake"))

    override suspend fun rejectRequest(requestId: String) = Unit

    override suspend fun rejectCountOf(requestId: String): Int = 0
}

/** Deterministic test identities: keys are fixed byte patterns, hashes are the real SHA-256 derivation. */
object InboundFixtures {
    /** Installs [identity] for [seed] plus placeholder private keys so [SelfIdentityCache] can serve it. */
    fun installIdentity(repository: FakeIdentityRepository, seed: Byte, displayName: String = "Me"): Identity {
        val identity = identity(seed, displayName)
        repository.identity = identity
        repository.ed25519Private = ByteArray(64) { (seed + 2).toByte() }
        repository.x25519StaticPrivate = ByteArray(32) { (seed + 3).toByte() }
        return identity
    }

    fun identity(seed: Byte, displayName: String = "Me"): Identity {
        val pub = ByteArray(32) { seed }
        val hash = UserHashEncoder.identityHashFromPublicKey(pub)
        return Identity(
            ed25519PublicKey = pub,
            identityHash = hash,
            userHash = UserHashEncoder.encode(hash),
            displayName = displayName,
            x25519StaticPublicKey = ByteArray(32) { (seed + 1).toByte() },
            createdAtUnixMs = 0L,
        )
    }

    fun peer(seed: Byte): PeerIdentity {
        val pub = ByteArray(32) { seed }
        return PeerIdentity(
            identityHash = UserHashEncoder.identityHashFromPublicKey(pub),
            ed25519PublicKey = pub,
            x25519StaticPublicKey = ByteArray(32) { (seed + 1).toByte() },
        )
    }

    fun contact(
        id: String,
        peer: PeerIdentity,
        status: ContactRelationshipStatus = ContactRelationshipStatus.APPROVED,
        blocked: Boolean = false,
    ): ContactEntity = ContactEntity(
        id = id,
        identityHash = peer.identityHash,
        ed25519Public = peer.ed25519PublicKey,
        x25519StaticPublic = peer.x25519StaticPublicKey,
        userHash = UserHashEncoder.encode(peer.identityHash),
        displayName = "Contact $id",
        verified = false,
        blocked = blocked,
        relationshipStatus = status,
        createdAtUnixMs = 0L,
        lastSeenUnixMs = null,
    )

    fun chatEnvelope(messageId: String, text: String = "hi", sentAtUnixMs: Long = System.currentTimeMillis()) =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(messageId))
            .setSentAtUnixMs(sentAtUnixMs)
            .setCounter(1)
            .setChat(ChatMessage.newBuilder().setText(text))
            .build()

    fun networkNodesEnvelope(relay: String = "wss://hint.example/relay"): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("node-exchange-1"))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setNetworkNodes(NetworkNodeList.newBuilder().addRelayAddresses(relay))
            .build()

    fun receiptEnvelope(
        refMessageId: String,
        type: ReceiptType,
        atUnixMs: Long,
        moreRefs: List<String> = emptyList(),
    ): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("receipt-$refMessageId"))
        .setSentAtUnixMs(atUnixMs)
        .setCounter(1)
        .setReceipt(
            Receipt.newBuilder()
                .setRefMessageId(ByteString.copyFromUtf8(refMessageId))
                .setType(type)
                .setAtUnixMs(atUnixMs)
                .addAllRefMessageIds(moreRefs.map { ByteString.copyFromUtf8(it) }),
        )
        .build()

    fun requestEnvelope(
        requestId: String,
        requesterIdentityPub: ByteArray = ByteArray(0),
        userHash: String = "",
        displayName: String = "",
    ): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("contact-req-$requestId"))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setCounter(1)
        .setContactRequest(
            ProtoContactRequest.newBuilder()
                .setRequestId(ByteString.copyFromUtf8(requestId))
                .setRequesterIdentityPub(ByteString.copyFrom(requesterIdentityPub))
                .setRequesterUserHash(userHash)
                .setRequesterDisplayName(displayName),
        )
        .build()

    fun responseEnvelope(
        requestId: String,
        type: ContactResponseType,
        responderIdentityPub: ByteArray = ByteArray(0),
        displayName: String = "",
    ): MessageEnvelope = MessageEnvelope.newBuilder()
        .setMessageId(ByteString.copyFromUtf8("contact-resp-$requestId"))
        .setSentAtUnixMs(System.currentTimeMillis())
        .setCounter(1)
        .setContactResponse(
            ContactResponse.newBuilder()
                .setRequestId(ByteString.copyFromUtf8(requestId))
                .setType(type)
                .setResponderIdentityPub(ByteString.copyFrom(responderIdentityPub))
                .setResponderUserHash("")
                .setResponderDisplayName(displayName),
        )
        .build()
}
