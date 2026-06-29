package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.common.network.NetworkPath
import ir.vmessenger.core.common.network.NetworkPathTracker
import ir.vmessenger.core.common.network.RelayPeerPolicy
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.data.repository.findByIdentityHash
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import ir.vmessenger.network.messaging.PeerRelayService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure user-relay circuit handling: policy gating, RelayReady replies, opaque frame forward.
 */
@Singleton
class PeerRelayForwarder @Inject constructor(
    private val peerRelayService: PeerRelayService,
    private val relayPeerPolicyManager: RelayPeerPolicyManager,
    private val contactDao: ContactDao,
    private val identityRepository: IdentityRepository,
    private val messagingService: MessagingService,
) {
    suspend fun handleOpen(incoming: IncomingEnvelope): Boolean {
        val session = incoming.session ?: return false
        if (!relayPeerPolicyManager.isRelayAllowedNow()) {
            AppLogger.warn("PeerRelay", "relay open rejected: policy gate")
            return false
        }
        if (relayPeerPolicyManager.policy == RelayPeerPolicy.CONTACTS_ONLY) {
            val contact = contactDao.getById(incoming.contactId)
            if (contact == null) {
                AppLogger.warn("PeerRelay", "relay open rejected: not a contact")
                return false
            }
        }
        val ready = peerRelayService.handleRelayOpen(
            incoming.envelope,
            incoming.envelope.senderIdentityHash.toByteArray(),
            policyAllowed = true,
        ) ?: return false
        runCatching { messagingService.sendProtocolReply(session, ready) }
            .onFailure { AppLogger.warn("PeerRelay", "RelayReady send failed: ${it.message}") }
        return true
    }

    suspend fun handleData(incoming: IncomingEnvelope): Boolean {
        if (!relayPeerPolicyManager.isRelayAllowedNow()) return false
        val data = incoming.envelope.relayData
        if (!peerRelayService.handleRelayData(incoming.envelope)) return false
        val circuit = peerRelayService.circuit(data.circuitId) ?: return false
        val targetHash = circuit.targetIdentityHash ?: return false
        val contact = contactDao.findByIdentityHash(targetHash) ?: run {
            AppLogger.warn("PeerRelay", "relay forward: target not in contacts")
            return false
        }
        val identity = identityRepository.getIdentity() ?: return false
        val self = PeerIdentity(
            identityHash = identity.identityHash,
            ed25519PublicKey = identity.ed25519PublicKey,
            x25519StaticPublicKey = identity.x25519StaticPublicKey,
            ed25519PrivateKey = identityRepository.getEd25519PrivateKey(),
            x25519StaticPrivateKey = identityRepository.getX25519StaticPrivateKey(),
        )
        val targetPeer = PeerIdentity(
            identityHash = contact.identityHash,
            ed25519PublicKey = contact.ed25519Public,
            x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(32),
        )
        return when (
            val result = messagingService.forwardOpaqueFrame(
                contactId = contact.id,
                self = self,
                peer = targetPeer,
                sealedSecureBody = data.encryptedFrame.toByteArray(),
            )
        ) {
            is AppResult.Success -> {
                NetworkPathTracker.record(NetworkPath.USER_RELAY, "circuit=${data.circuitId}")
                true
            }
            is AppResult.Error -> {
                AppLogger.warn("PeerRelay", "forward failed: ${result.error.message}")
                false
            }
        }
    }
}
