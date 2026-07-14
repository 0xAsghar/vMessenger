package ir.vmessenger.data.network

import android.content.Context
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.location.LocationService
import ir.vmessenger.core.location.LocationUpdate
import ir.vmessenger.core.location.LocationUpdateBus
import ir.vmessenger.core.proto.app.v1.Control
import ir.vmessenger.core.proto.app.v1.ControlType
import ir.vmessenger.core.proto.app.v1.LocationPacket
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.di.IoDispatcher
import ir.vmessenger.domain.repository.IdentityRepository
import ir.vmessenger.domain.repository.LocationAccessRepository
import ir.vmessenger.domain.repository.LocationRepository
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.domain.model.Identity

@Singleton
class LocationSharingCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationRepository: LocationRepository,
    private val locationAccessRepository: LocationAccessRepository,
    private val locationShareDao: LocationShareDao,
    private val contactDao: ContactDao,
    private val identityRepository: IdentityRepository,
    private val messagingService: MessagingService,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val outgoingShareIds = mutableMapOf<String, String>()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            restoreActiveOutgoingShares()
            LocationUpdateBus.updates.collect { update ->
                onLocationUpdate(update)
            }
        }
    }

    /**
     * Rebuild in-memory outgoing share state after a process restart. Shares stay
     * active in the DB, but without this the coordinator forgot them and location
     * updates were silently no longer recorded or sent.
     */
    private suspend fun restoreActiveOutgoingShares() {
        val active = locationShareDao.observeActive().first()
            .filter { it.direction == MessageDirection.OUTGOING }
        if (active.isEmpty()) return
        for (share in active) {
            outgoingShareIds[share.contactId] = share.shareId
        }
        AppLogger.info("Location", "restored ${active.size} active outgoing share(s)")
        runCatching { LocationService.start(context) }
            .onFailure { AppLogger.warn("Location", "service restart failed: ${it.message}") }
    }

    suspend fun startSharingToGrantedContacts(): AppResult<Unit> {
        val granted = locationAccessRepository.grantedContactIds()
        if (granted.isEmpty()) return noContactSelectedError()
        LocationService.start(context)
        val started = startSharesFor(granted)
        return if (started == 0) {
            // Nothing actually started (e.g. selected contacts not approved yet);
            // report it instead of leaving the UI silently in the "off" state.
            LocationService.stop(context)
            noContactSelectedError()
        } else {
            AppResult.Success(Unit)
        }
    }

    private suspend fun startSharesFor(granted: List<String>): Int {
        var started = 0
        for (contactId in granted) {
            val contact = contactDao.getById(contactId) ?: continue
            if (contact.relationshipStatus != ContactRelationshipStatus.APPROVED) continue
            when (val result = locationRepository.startSharing(contactId)) {
                is AppResult.Success -> {
                    started++
                    outgoingShareIds[contactId] = result.data
                    sendShareStart(contactId, result.data)
                }
                is AppResult.Error -> Unit
            }
        }
        return started
    }

    private fun noContactSelectedError(): AppResult<Unit> =
        AppResult.Error(ir.vmessenger.core.common.AppError.Validation("هیچ مخاطبی انتخاب نشده"))

    suspend fun stopAllSharing() {
        val shares = locationShareDao.observeActive().first()
            .filter { it.direction == MessageDirection.OUTGOING }
        AppLogger.info("Location", "stopAllSharing: ${shares.size} outgoing share(s)")
        // Mark inactive first so the UI flips to "start" immediately, then stop
        // the service and best-effort notify peers (a slow/failed send must not
        // leave the share stuck active).
        for (share in shares) {
            locationRepository.stopSharing(share.shareId)
        }
        outgoingShareIds.clear()
        LocationService.stop(context)
        for (share in shares) {
            runCatching { sendShareStop(share.contactId, share.shareId) }
                .onFailure { AppLogger.warn("Location", "share stop notify failed: ${it.message}") }
        }
    }

    suspend fun handleIncomingLocation(contactId: String, envelope: MessageEnvelope) {
        val contact = contactDao.getById(contactId) ?: return
        if (contact.relationshipStatus != ContactRelationshipStatus.APPROVED) return
        val packet = envelope.location
        val shareId = packet.shareId.toStringUtf8()
        val existing = locationShareDao.getById(shareId)
        if (existing == null) {
            locationRepository.startIncomingShare(contactId, shareId)
        }
        if (packet.isFinal) {
            locationRepository.stopIncomingShare(shareId)
            return
        }
        locationRepository.recordSample(
            shareId = shareId,
            latitude = packet.latitude,
            longitude = packet.longitude,
            accuracyM = packet.accuracyM,
            batteryPct = packet.batteryPct.takeIf { it != 0 },
        )
    }

    suspend fun handleIncomingControl(contactId: String, envelope: MessageEnvelope) {
        val control = envelope.control
        when (control.type) {
            ControlType.CONTROL_TYPE_LOCATION_SHARE_START -> {
                // The share row is created by the first LocationPacket, which
                // carries the sender's real shareId. Creating one here with a
                // random id left a phantom share that never received samples
                // and could never be matched to the sender's stop.
                Unit
            }
            ControlType.CONTROL_TYPE_LOCATION_SHARE_STOP -> {
                // Only stop the INCOMING share for this contact; the unfiltered
                // lookup could return (and kill) our own outgoing share instead.
                locationShareDao
                    .getActiveByContactAndDirection(contactId, MessageDirection.INCOMING)
                    ?.let { locationRepository.stopIncomingShare(it.shareId) }
            }
            else -> Unit
        }
    }

    private suspend fun onLocationUpdate(update: LocationUpdate) {
        for ((contactId, shareId) in outgoingShareIds) {
            locationRepository.recordSample(
                shareId = shareId,
                latitude = update.latitude,
                longitude = update.longitude,
                accuracyM = update.accuracyM,
                batteryPct = null,
            )
            sendLocationPacket(contactId, shareId, update)
        }
    }

    private suspend fun sendShareStart(contactId: String, shareId: String) {
        val identity = identityRepository.getIdentity() ?: return
        val contact = contactDao.getById(contactId) ?: return
        val self = selfPeer(identity)
        val peer = peerFromContact(contact)
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("loc-start-$shareId"))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setControl(Control.newBuilder().setType(ControlType.CONTROL_TYPE_LOCATION_SHARE_START))
            .build()
        messagingService.send(contactId, self, peer, envelope)
    }

    private suspend fun sendShareStop(contactId: String, shareId: String) {
        val identity = identityRepository.getIdentity() ?: return
        val contact = contactDao.getById(contactId) ?: return
        val self = selfPeer(identity)
        val peer = peerFromContact(contact)
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("loc-stop-$shareId"))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setControl(Control.newBuilder().setType(ControlType.CONTROL_TYPE_LOCATION_SHARE_STOP))
            .build()
        messagingService.send(contactId, self, peer, envelope)
        sendFinalPacket(contactId, shareId, self, peer)
    }

    private suspend fun sendLocationPacket(contactId: String, shareId: String, update: LocationUpdate) {
        val identity = identityRepository.getIdentity() ?: return
        val contact = contactDao.getById(contactId) ?: return
        val self = selfPeer(identity)
        val peer = peerFromContact(contact)
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("loc-${update.sampledAtUnixMs}"))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(update.sampledAtUnixMs)
            .setCounter(1)
            .setLocation(
                LocationPacket.newBuilder()
                    .setShareId(ByteString.copyFromUtf8(shareId))
                    .setLatitude(update.latitude)
                    .setLongitude(update.longitude)
                    .setAccuracyM(update.accuracyM)
                    .setSampledAtUnixMs(update.sampledAtUnixMs),
            )
            .build()
        messagingService.send(contactId, self, peer, envelope)
    }

    private suspend fun sendFinalPacket(
        contactId: String,
        shareId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
    ) {
        val identity = identityRepository.getIdentity() ?: return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8("loc-final-$shareId"))
            .setSenderIdentityHash(ByteString.copyFrom(identity.identityHash))
            .setSentAtUnixMs(System.currentTimeMillis())
            .setCounter(1)
            .setLocation(
                LocationPacket.newBuilder()
                    .setShareId(ByteString.copyFromUtf8(shareId))
                    .setIsFinal(true),
            )
            .build()
        messagingService.send(contactId, self, peer, envelope)
    }

    private suspend fun selfPeer(identity: Identity) = PeerIdentity(
        identityHash = identity.identityHash,
        ed25519PublicKey = identity.ed25519PublicKey,
        x25519StaticPublicKey = identity.x25519StaticPublicKey,
        ed25519PrivateKey = identityRepository.getEd25519PrivateKey(),
        x25519StaticPrivateKey = identityRepository.getX25519StaticPrivateKey(),
    )

    private fun peerFromContact(contact: ir.vmessenger.core.database.entity.ContactEntity) = PeerIdentity(
        identityHash = contact.identityHash,
        ed25519PublicKey = contact.ed25519Public,
        x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(32),
    )
}
