package ir.vmessenger.data.backup

import com.google.protobuf.ByteString
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.LocationAccessEntity
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.backup.v1.BackupContact
import ir.vmessenger.core.proto.backup.v1.BackupConversation
import ir.vmessenger.core.proto.backup.v1.BackupIdentity
import ir.vmessenger.core.proto.backup.v1.BackupLocationAccess
import ir.vmessenger.core.proto.backup.v1.BackupMessage
import ir.vmessenger.core.proto.backup.v1.BackupNode
import ir.vmessenger.core.proto.backup.v1.BackupPayload
import ir.vmessenger.data.AppVersionName
import ir.vmessenger.domain.model.BackupOptions
import ir.vmessenger.domain.model.Identity
import ir.vmessenger.domain.model.NetworkNode
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the plaintext [BackupPayload] from the local database. User-hash strings are deliberately not
 * exported (they are recomputed on import) and attachment files/paths are dropped (metadata only).
 */
@Singleton
class BackupPayloadExporter @Inject constructor(
    private val store: BackupStore,
    @AppVersionName private val appVersion: String,
) {
    /**
     * The private key arrays are copied into the message; the caller keeps ownership and zeroizes them.
     * The resulting message holds immutable copies that cannot be wiped, so serialize it promptly.
     */
    suspend fun build(
        identity: Identity,
        ed25519Private: ByteArray,
        x25519StaticPrivate: ByteArray,
        options: BackupOptions,
    ): BackupPayload {
        val builder = BackupPayload.newBuilder()
            .setPayloadVersion(PAYLOAD_VERSION)
            .setCreatedAtUnixMs(System.currentTimeMillis())
            .setAppVersion(appVersion)
            .setIdentity(identity.toBackup(ed25519Private, x25519StaticPrivate))
            .addAllContacts(store.contactDao.getAll().map { it.toBackup() })
            .addAllLocationAccess(store.locationAccessDao.observeAll().first().map { it.toBackup() })
            .addAllUserNodes(userNodes())
        if (options.includeConversations) builder.addAllConversations(conversations())
        return builder.build()
    }

    private suspend fun conversations(): List<BackupConversation> =
        store.conversationDao.observeAll().first().map { conversation ->
            val messages = store.messageDao.observeConversation(conversation.id).first()
            BackupConversation.newBuilder()
                .setId(conversation.id)
                .setContactId(conversation.contactId)
                .setMuted(conversation.muted)
                .addAllMessages(messages.map { it.toBackup() })
                .build()
        }

    private suspend fun userNodes(): List<BackupNode> =
        store.nodeRepository.observeNodes().first()
            .filter { it.source == NetworkNode.SOURCE_USER }
            .map { BackupNode.newBuilder().setAddress(it.address).setRole(it.role.name).build() }

    private fun Identity.toBackup(ed25519Private: ByteArray, x25519StaticPrivate: ByteArray): BackupIdentity =
        BackupIdentity.newBuilder()
            .setEd25519Public(ByteString.copyFrom(ed25519PublicKey))
            .setEd25519Private(ByteString.copyFrom(ed25519Private))
            .setX25519StaticPublic(ByteString.copyFrom(x25519StaticPublicKey))
            .setX25519StaticPrivate(ByteString.copyFrom(x25519StaticPrivate))
            .setDisplayName(displayName)
            .setCreatedAtUnixMs(createdAtUnixMs)
            .build()

    private fun ContactEntity.toBackup(): BackupContact =
        BackupContact.newBuilder()
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

    private fun MessageEntity.toBackup(): BackupMessage =
        BackupMessage.newBuilder()
            .setMessageId(messageId)
            .setDirection(direction.name)
            .setContentType(contentType.name)
            .setBody(body.orEmpty())
            .setStatus(status.name)
            .setCreatedAtUnixMs(createdAtUnixMs)
            .setSentAtUnixMs(sentAtUnixMs ?: 0L)
            .setDeliveredAtUnixMs(deliveredAtUnixMs ?: 0L)
            .setReadAtUnixMs(readAtUnixMs ?: 0L)
            .setReplyToMessageId(replyToMessageId.orEmpty())
            .setAttachmentName(attachmentName.orEmpty())
            .setAttachmentMimeType(attachmentMimeType.orEmpty())
            .setAttachmentSizeBytes(attachmentSizeBytes ?: 0L)
            .build()

    private fun LocationAccessEntity.toBackup(): BackupLocationAccess =
        BackupLocationAccess.newBuilder()
            .setContactId(contactId)
            .setCanSeeMyLocation(canSeeMyLocation)
            .build()

    companion object {
        const val PAYLOAD_VERSION = 1
    }
}
