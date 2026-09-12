package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.entity.GroupEntity
import ir.vmessenger.core.database.entity.GroupMemberEntity
import ir.vmessenger.core.proto.app.v1.GroupControlType
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.network.messaging.PeerIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts membership changes on the wire.
 *
 * Two different needs, two paths. A change the user made is queued like any other
 * message, so it retries, backs off and can reach a member who is offline right
 * now. A snapshot — sent to repair a device that fell out of sequence — is sent
 * directly and never retried: if it does not land, the next gap will ask again,
 * and a queue of stale snapshots would be worse than none.
 */
@Singleton
@Suppress("LongParameterList") // the DAOs the fan-out reads, plus our identity and the transport
class GroupControlSender @Inject constructor(
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val messaging: MessagingPort,
    private val selfIdentity: SelfIdentityCache,
) {
    /** Answers a peer that asked for the authoritative membership. */
    suspend fun sendSnapshot(group: GroupEntity, recipients: List<String>) {
        val members = groupDao.activeMembers(group.id)
        send(group, GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT, members, group.version, recipients)
    }

    /** Asks the creator for a snapshot after spotting a gap in the version sequence. */
    suspend fun requestSnapshot(group: GroupEntity, creatorKey: String) {
        send(
            group = group,
            type = GroupControlType.GROUP_CONTROL_TYPE_SNAPSHOT_REQUEST,
            members = emptyList(),
            version = group.version,
            recipients = listOf(creatorKey),
        )
    }

    @Suppress("LongParameterList") // mirrors the control's own fields
    private suspend fun send(
        group: GroupEntity,
        type: GroupControlType,
        members: List<GroupMemberEntity>,
        version: Long,
        recipients: List<String>,
    ) {
        val self = selfIdentity.get() ?: return
        val envelope = GroupControlCodec.envelope(self.identityHash, group, type, members, version)
        for (recipient in recipients) {
            deliver(recipient, self, envelope)
        }
    }

    private suspend fun deliver(recipientKey: String, self: PeerIdentity, envelope: MessageEnvelope) {
        val contact = contactDao.getByRoutingKey(recipientKey) ?: return
        val peer = PeerIdentity(
            identityHash = contact.identityHash,
            ed25519PublicKey = contact.ed25519Public,
            x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
        )
        val result = messaging.send(contact.id, self, peer, envelope)
        if (result is AppResult.Error) {
            AppLogger.info(TAG, "control to $recipientKey not delivered now: ${result.error.message}")
        }
    }

    private companion object {
        const val TAG = "Groups"
        const val X25519_KEY_SIZE = 32
    }
}
