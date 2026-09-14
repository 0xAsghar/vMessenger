package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.IdentityDao
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.IdentityEntity
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.core.proto.app.v1.ProfileUpdate
import ir.vmessenger.data.repository.ConversationWriter
import ir.vmessenger.domain.repository.ProfileBroadcaster
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells approved contacts that our display name or photo changed.
 *
 * Before this, a name reached a peer exactly once — inside the pairing handshake — so renaming
 * yourself was invisible to everyone who already had you, permanently. The update goes through
 * the same per-recipient outbox as everything else, which is the point: the contacts who most need
 * telling are the ones who are not online right now.
 *
 * Every send carries the *whole* profile at a monotonic revision rather than a delta, so a
 * receiver that missed one is not left applying half of it, and one that arrives late is dropped
 * on the number alone.
 */
@Singleton
class ProfileUpdateSender @Inject constructor(
    private val identityDao: IdentityDao,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val conversationWriter: ConversationWriter,
) : ProfileBroadcaster {
    override suspend fun broadcastProfile() {
        val identity = identityDao.getIdentity() ?: return
        val revision = identity.avatarRevision + 1
        identityDao.insertIdentity(identity.copy(avatarRevision = revision))
        val envelope = envelopeFor(identity, revision)
        val approved = contactDao.getAll().filter { it.canBeTold() }
        if (approved.isEmpty()) return
        AppLogger.info("Messaging", "profile update rev=$revision to ${approved.size} contact(s)")
        for (contact in approved) {
            // One control per contact rather than one fan-out: these are separate 1:1
            // conversations, not a group, and each needs its own outbox row anyway.
            // A contact we have never exchanged a message with has no conversation row, and
            // creating one to carry a rename would put an empty thread in their chat list. They
            // learn the new name from the first message either side sends instead.
            val conversationId = conversationDao.getByContactId(contact.id)?.id ?: continue
            conversationWriter.queueMessageControl(
                conversationId = conversationId,
                envelope = envelope.toByteArray(),
                recipients = listOf(IdentityHashMatcher.routingKeyHex(contact.identityHash)),
            )
        }
    }

    private fun envelopeFor(identity: IdentityEntity, revision: Long): MessageEnvelope =
        MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setProfileUpdate(
                ProfileUpdate.newBuilder()
                    .setDisplayName(identity.displayName)
                    .setUpdatedAtUnixMs(System.currentTimeMillis())
                    .setRevision(revision.toULong().toLong()),
            )
            .build()

    private fun ContactEntity.canBeTold(): Boolean =
        !blocked && relationshipStatus == ContactRelationshipStatus.APPROVED
}
