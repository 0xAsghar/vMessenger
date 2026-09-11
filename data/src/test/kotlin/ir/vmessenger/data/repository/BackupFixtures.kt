package ir.vmessenger.data.repository

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.backup.BackupBundleCodec
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.backup.v1.BackupContact
import ir.vmessenger.core.proto.backup.v1.BackupConversation
import ir.vmessenger.core.proto.backup.v1.BackupIdentity
import ir.vmessenger.core.proto.backup.v1.BackupMessage
import ir.vmessenger.core.proto.backup.v1.BackupPayload
import ir.vmessenger.data.backup.BackupPayloadExporter

/** Builders for the entities and protobuf messages the backup tests exchange. */
class BackupFixtures(
    private val cryptoEngine: CryptoEngine,
    private val codec: BackupBundleCodec,
    private val passphrase: CharArray,
) {
    fun backupIdentity(displayName: String): BackupIdentity {
        val ed25519 = cryptoEngine.generateEd25519KeyPair()
        val x25519 = cryptoEngine.generateX25519KeyPair()
        return BackupIdentity.newBuilder()
            .setEd25519Public(ByteString.copyFrom(ed25519.publicKey))
            .setEd25519Private(ByteString.copyFrom(ed25519.privateKey))
            .setX25519StaticPublic(ByteString.copyFrom(x25519.publicKey))
            .setX25519StaticPrivate(ByteString.copyFrom(x25519.privateKey))
            .setDisplayName(displayName)
            .setCreatedAtUnixMs(CREATED_AT)
            .build()
    }

    fun payloadWith(
        identity: BackupIdentity = backupIdentity("Ali"),
        contacts: List<BackupContact> = emptyList(),
        conversations: List<BackupConversation> = emptyList(),
    ): BackupPayload = BackupPayload.newBuilder()
        .setPayloadVersion(BackupPayloadExporter.PAYLOAD_VERSION)
        .setCreatedAtUnixMs(CREATED_AT)
        .setAppVersion("test")
        .setIdentity(identity)
        .addAllContacts(contacts)
        .addAllConversations(conversations)
        .build()

    /** Encodes with the cheapest allowed KDF parameters so the suite stays fast. */
    fun fastBundle(payload: BackupPayload): ByteArray =
        codec.encode(payload.toByteArray(), passphrase, opsLimit = 1, memLimitBytes = 8L * 1024 * 1024)

    fun approvedContact(id: String, name: String): ContactEntity {
        val ed25519 = cryptoEngine.generateEd25519KeyPair()
        val x25519 = cryptoEngine.generateX25519KeyPair()
        val identityHash = UserHashEncoder.identityHashFromPublicKey(ed25519.publicKey)
        return ContactEntity(
            id = id,
            identityHash = identityHash,
            ed25519Public = ed25519.publicKey,
            x25519StaticPublic = x25519.publicKey,
            userHash = UserHashEncoder.encode(identityHash),
            displayName = name,
            verified = true,
            blocked = false,
            relationshipStatus = ContactRelationshipStatus.APPROVED,
            createdAtUnixMs = 42L,
            lastSeenUnixMs = null,
        )
    }

    fun textMessage(id: String, conversationId: String, direction: MessageDirection, at: Long): MessageEntity =
        MessageEntity(
            messageId = id,
            conversationId = conversationId,
            direction = direction,
            contentType = MessageContentType.TEXT,
            body = "hello",
            replyToMessageId = null,
            status = DeliveryStatus.DELIVERED,
            createdAtUnixMs = at,
            sentAtUnixMs = null,
            deliveredAtUnixMs = null,
            readAtUnixMs = null,
        )

    companion object {
        private const val CREATED_AT = 1_700_000_000_000L
    }
}

fun ContactEntity.toBackup(): BackupContact = BackupContact.newBuilder()
    .setId(id)
    .setIdentityHash(ByteString.copyFrom(identityHash))
    .setEd25519Public(ByteString.copyFrom(ed25519Public))
    .setX25519StaticPublic(x25519StaticPublic?.let(ByteString::copyFrom) ?: ByteString.EMPTY)
    .setDisplayName(displayName)
    .setVerified(verified)
    .setBlocked(blocked)
    .setRelationshipStatus(relationshipStatus.name)
    .setCreatedAtUnixMs(createdAtUnixMs)
    .build()

fun MessageEntity.toBackup(text: String): BackupMessage = BackupMessage.newBuilder()
    .setMessageId(messageId)
    .setDirection(direction.name)
    .setContentType(contentType.name)
    .setBody(text)
    .setStatus(status.name)
    .setCreatedAtUnixMs(createdAtUnixMs)
    .build()
