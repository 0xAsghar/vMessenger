package ir.vmessenger.data.network

import android.content.Context
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.concurrency.loggingExceptionHandler
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.LocationSampleDao
import ir.vmessenger.core.database.dao.LocationShareDao
import ir.vmessenger.core.database.entity.ContactEntity
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
import ir.vmessenger.domain.repository.LocationAccessRepository
import ir.vmessenger.domain.repository.LocationRepository
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Starts/stops the foreground location service; an interface so the coordinator is JVM-testable. */
interface LocationServiceControl {
    fun start()
    fun stop()
}

@Singleton
class AndroidLocationServiceControl @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationServiceControl {
    override fun start() = LocationService.start(context)

    override fun stop() = LocationService.stop(context)
}

/**
 * Owns live location sharing in both directions: fans our position out to the
 * contacts we share with, and turns a contact's packets into their share/samples.
 *
 * Inbound rules: the sender must pass [InboundPolicy] (approved, not blocked),
 * a contact has one active inbound share at a time (a new share id replaces the
 * old one), a share id can never be reused by another contact, and samples are
 * accepted at most once per [MIN_SAMPLE_INTERVAL_MS] per share.
 *
 * Retention: an hourly job drops samples older than [RETENTION_WINDOW_MS],
 * keeps at most [MAX_SAMPLES_PER_SHARE] per share and removes shares that
 * ended more than [RETENTION_WINDOW_MS] ago.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // one port per collaborator; both share directions live here
class LocationSharingCoordinator @Inject constructor(
    private val locationRepository: LocationRepository,
    private val locationAccessRepository: LocationAccessRepository,
    private val locationShareDao: LocationShareDao,
    private val locationSampleDao: LocationSampleDao,
    private val contactDao: ContactDao,
    private val selfIdentityCache: SelfIdentityCache,
    private val messaging: MessagingPort,
    private val locationServiceControl: LocationServiceControl,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + loggingExceptionHandler("Location"))

    /** contactId -> active outgoing shareId. Concurrent: location updates, start/stop and cleanup race. */
    private val outgoingShareIds = ConcurrentHashMap<String, String>()

    /** shareId -> wall-clock time of the last accepted inbound sample. */
    private val lastInboundSampleAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var started = false

    /**
     * Serialises the start and stop paths.
     *
     * Starting a share awaits a network send per contact, which can take seconds behind a relay.
     * Without this, tapping the switch off during that window let `stopAllSharing` clear the map
     * and stop the service while the start loop kept going — writing fresh active share rows and
     * telling those peers a share had begun, with the UI reading "off" and the service down.
     */
    private val shareMutex = Mutex()

    private val grantReconciler = LocationGrantReconciler(
        locationAccessRepository,
        object : LocationGrantReconciler.Target {
            override fun sharedWith(): Set<String> = outgoingShareIds.keys.toSet()
            override suspend fun startSharingWith(contactIds: List<String>) {
                startSharesFor(contactIds)
            }
            override suspend fun revokeSharingWith(contactId: String) = revokeShareWith(contactId)
        },
    )

    fun start() {
        if (started) return
        started = true
        scope.launch { grantReconciler.run() }
        scope.launch { watchServiceLiveness() }
        scope.launch {
            restoreActiveOutgoingShares()
            LocationUpdateBus.updates.collect { update ->
                onLocationUpdate(update)
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { runRetention(System.currentTimeMillis()) }
                    .onFailure { AppLogger.warn("Location", "retention pass failed: ${it.message}") }
                delay(RETENTION_INTERVAL_MS)
            }
        }
    }

    /** Stops the location fan-out and the retention loop (coordinator stop); [start] resumes both. */
    fun stop() {
        started = false
        scope.coroutineContext.cancelChildren()
    }

    /**
     * One retention pass as of [now]: samples outside the window go, each share
     * keeps its newest [MAX_SAMPLES_PER_SHARE], and shares that ended more than
     * a window ago are removed (their samples cascade).
     */
    suspend fun runRetention(now: Long) {
        val cutoff = now - RETENTION_WINDOW_MS
        locationSampleDao.purgeOlderThan(cutoff)
        for (shareId in locationShareDao.allShareIds()) {
            locationSampleDao.deleteExcessForShare(shareId, MAX_SAMPLES_PER_SHARE)
        }
        locationShareDao.deleteEndedBefore(cutoff)
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
        runCatching { locationServiceControl.start() }
            .onFailure { AppLogger.warn("Location", "service restart failed: ${it.message}") }
    }

    suspend fun startSharingToGrantedContacts(): AppResult<Unit> {
        val granted = locationAccessRepository.grantedContactIds()
        if (granted.isEmpty()) return noContactSelectedError()
        val started = shareMutex.withLock { startSharesFor(granted) }
        // Reported rather than left silently "off": the usual cause is that every ticked contact
        // is still pending approval.
        return if (started == 0) noContactSelectedError() else AppResult.Success(Unit)
    }

    /**
     * Caller must hold [shareMutex].
     *
     * Starts the foreground service itself, and only once a share actually exists. The service
     * used to be started by the one caller that knew to, which meant the reconciler could create
     * a share with nothing running behind it — the peer was told a share had begun and then never
     * received a position. Starting it up front also risked a `startForegroundService` with no
     * matching `startForeground` when every ticked contact turned out to be unapproved.
     */
    private suspend fun startSharesFor(granted: List<String>): Int {
        var started = 0
        for (contactId in granted) {
            val contact = contactDao.getById(contactId)?.takeIf { it.canReceiveOurLocation() } ?: continue
            // Re-read per contact rather than trusting the snapshot: un-ticking someone while the
            // loop is still awaiting an earlier send must not still start a share for them.
            if (!locationAccessRepository.isGranted(contactId)) continue
            when (val result = locationRepository.startSharing(contact.id)) {
                is AppResult.Success -> {
                    if (started == 0) locationServiceControl.start()
                    started++
                    outgoingShareIds[contact.id] = result.data
                    sendControl(contact.id, "loc-start-${result.data}", ControlType.CONTROL_TYPE_LOCATION_SHARE_START)
                }
                is AppResult.Error -> Unit
            }
        }
        return started
    }

    /**
     * Brings the location service back if it dies under a live share.
     *
     * The service is deliberately START_NOT_STICKY, because a sticky restart used to re-register
     * the GPS after a process kill even when the user had turned sharing off. That leaves the
     * opposite gap: an OEM battery manager killing it mid-share would silently end delivery while
     * the switch still read "on". Restarting is gated on a share actually being active, which is
     * the distinction the sticky flag could not make.
     */
    private suspend fun watchServiceLiveness() {
        LocationUpdateBus.serviceRunning.collect { running ->
            if (running || outgoingShareIds.isEmpty()) return@collect
            AppLogger.warn("Location", "service died under ${outgoingShareIds.size} active share(s); restarting")
            runCatching { locationServiceControl.start() }
                .onFailure { AppLogger.warn("Location", "service restart failed: ${it.message}") }
        }
    }

    private fun ContactEntity.canReceiveOurLocation(): Boolean =
        relationshipStatus == ContactRelationshipStatus.APPROVED && !blocked

    private fun noContactSelectedError(): AppResult<Unit> =
        AppResult.Error(AppError.Validation("هیچ مخاطبی انتخاب نشده"))

    suspend fun stopAllSharing() = shareMutex.withLock { stopAllSharingLocked() }

    private suspend fun stopAllSharingLocked() {
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
        locationServiceControl.stop()
        // Bounded as a whole: each notify dials a peer, and the notification's stop action runs
        // this inside a broadcast receiver's goAsync window. Peers that cannot be reached in time
        // learn the share ended from the final packet or its absence; blowing the budget would
        // risk the process being killed before the earlier peers were told at all.
        withTimeoutOrNull(STOP_NOTIFY_BUDGET_MS) {
            for (share in shares) {
                runCatching { sendShareStop(share.contactId, share.shareId) }
                    .onFailure { AppLogger.warn("Location", "share stop notify failed: ${it.message}") }
            }
        } ?: AppLogger.warn("Location", "stop notifications timed out; shares are already inactive locally")
    }

    /**
     * Ends our outgoing share with one contact because they were un-ticked in the picker, and tells
     * them so their map stops showing a position that will never update again. The block/delete
     * path below is deliberately silent instead; this one is not, because the contact remains a
     * contact and a stale pin is worse than no pin.
     */
    private suspend fun revokeShareWith(contactId: String) {
        val shareId = outgoingShareIds.remove(contactId) ?: return
        locationRepository.stopSharing(shareId)
        runCatching { sendShareStop(contactId, shareId) }
            .onFailure { AppLogger.warn("Location", "share revoke notify failed: ${it.message}") }
        if (outgoingShareIds.isEmpty()) {
            runCatching { locationServiceControl.stop() }
                .onFailure { AppLogger.warn("Location", "service stop failed: ${it.message}") }
        }
    }

    /**
     * Stops sharing with one contact in both directions without telling them
     * (the contact was blocked or is being deleted). Stops the location service
     * once no outgoing share remains.
     */
    suspend fun stopSharingWith(contactId: String) {
        val outgoing = outgoingShareIds.remove(contactId)
        outgoing?.let { locationRepository.stopSharing(it) }
        locationShareDao.getActiveByContactAndDirection(contactId, MessageDirection.OUTGOING)
            ?.let { locationRepository.stopSharing(it.shareId) }
        locationShareDao.getActiveByContactAndDirection(contactId, MessageDirection.INCOMING)
            ?.let { endIncomingShare(it.shareId) }
        if (outgoing != null && outgoingShareIds.isEmpty()) {
            runCatching { locationServiceControl.stop() }
                .onFailure { AppLogger.warn("Location", "service stop failed: ${it.message}") }
        }
    }

    suspend fun handleIncomingLocation(contactId: String, envelope: MessageEnvelope) {
        val contact = contactDao.getById(contactId)
        if (!InboundPolicy.allows(contact, InboundKind.LOCATION)) {
            AppLogger.warn("Location", "location ignored: sender not allowed contact=$contactId")
            return
        }
        val packet = envelope.location
        val shareId = packet.shareId.toStringUtf8()
        when {
            !resolveIncomingShare(contactId, shareId) -> Unit
            packet.isFinal -> endIncomingShare(shareId)
            isPlausible(packet) && acceptsSample(shareId, System.currentTimeMillis()) ->
                locationRepository.recordSample(
                    shareId = shareId,
                    latitude = packet.latitude,
                    longitude = packet.longitude,
                    accuracyM = packet.accuracyM,
                    batteryPct = packet.batteryPct.takeIf { it != 0 },
                )
        }
    }

    /**
     * Makes [shareId] this contact's single active inbound share. An unknown id
     * replaces whatever share the contact had; a known id is only valid while it
     * is still the contact's own active inbound share (never another contact's).
     */
    private suspend fun resolveIncomingShare(contactId: String, shareId: String): Boolean {
        if (shareId.isBlank() || shareId.length > MAX_SHARE_ID_LENGTH) return false
        val existing = locationShareDao.getById(shareId)
        return if (existing != null) {
            existing.contactId == contactId && existing.direction == MessageDirection.INCOMING && existing.active
        } else {
            locationShareDao.getActiveByContactAndDirection(contactId, MessageDirection.INCOMING)?.let { previous ->
                AppLogger.info("Location", "incoming share replaced contact=$contactId")
                endIncomingShare(previous.shareId)
            }
            locationRepository.startIncomingShare(contactId, shareId)
            true
        }
    }

    private suspend fun endIncomingShare(shareId: String) {
        locationRepository.stopIncomingShare(shareId)
        lastInboundSampleAt.remove(shareId)
    }

    private fun isPlausible(packet: LocationPacket): Boolean =
        packet.latitude in -MAX_LATITUDE..MAX_LATITUDE && packet.longitude in -MAX_LONGITUDE..MAX_LONGITUDE

    /** Rate limit: at most one sample per [MIN_SAMPLE_INTERVAL_MS] per share; extras are dropped. */
    internal fun acceptsSample(shareId: String, nowMs: Long): Boolean {
        var accepted = false
        lastInboundSampleAt.compute(shareId) { _, last ->
            if (last == null || nowMs - last >= MIN_SAMPLE_INTERVAL_MS) {
                accepted = true
                nowMs
            } else {
                last
            }
        }
        return accepted
    }

    suspend fun handleIncomingControl(contactId: String, envelope: MessageEnvelope) {
        if (!InboundPolicy.allows(contactDao.getById(contactId), InboundKind.CONTROL)) {
            AppLogger.warn("Location", "control ignored: sender not allowed contact=$contactId")
            return
        }
        when (envelope.control.type) {
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
                    ?.let { endIncomingShare(it.shareId) }
            }
            else -> Unit
        }
    }

    /** Iterates a snapshot: shares may start or stop while a round of sends is in flight. */
    internal suspend fun onLocationUpdate(update: LocationUpdate) {
        for ((contactId, shareId) in outgoingShareIds.toMap()) {
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

    private suspend fun sendShareStop(contactId: String, shareId: String) {
        sendControl(contactId, "loc-stop-$shareId", ControlType.CONTROL_TYPE_LOCATION_SHARE_STOP)
        send(contactId, "loc-final-$shareId", System.currentTimeMillis()) {
            setLocation(LocationPacket.newBuilder().setShareId(ByteString.copyFromUtf8(shareId)).setIsFinal(true))
        }
    }

    private suspend fun sendControl(contactId: String, messageId: String, type: ControlType) {
        send(contactId, messageId, System.currentTimeMillis()) {
            setControl(Control.newBuilder().setType(type))
        }
    }

    private suspend fun sendLocationPacket(contactId: String, shareId: String, update: LocationUpdate) {
        send(contactId, "loc-${update.sampledAtUnixMs}", update.sampledAtUnixMs) {
            setLocation(
                LocationPacket.newBuilder()
                    .setShareId(ByteString.copyFromUtf8(shareId))
                    .setLatitude(update.latitude)
                    .setLongitude(update.longitude)
                    .setAccuracyM(update.accuracyM)
                    .setSampledAtUnixMs(update.sampledAtUnixMs),
            )
        }
    }

    private suspend fun send(
        contactId: String,
        messageId: String,
        sentAtUnixMs: Long,
        body: MessageEnvelope.Builder.() -> Unit,
    ) {
        val self = selfIdentityCache.get()
        val contact = contactDao.getById(contactId)
        if (self == null || contact == null) return
        val envelope = MessageEnvelope.newBuilder()
            .setMessageId(ByteString.copyFromUtf8(messageId))
            .setSenderIdentityHash(ByteString.copyFrom(self.identityHash))
            .setSentAtUnixMs(sentAtUnixMs)
            .setCounter(1)
            .apply(body)
            .build()
        messaging.send(contactId, self, peerFromContact(contact), envelope)
    }

    private fun peerFromContact(contact: ContactEntity) = PeerIdentity(
        identityHash = contact.identityHash,
        ed25519PublicKey = contact.ed25519Public,
        x25519StaticPublicKey = contact.x25519StaticPublic ?: ByteArray(X25519_KEY_SIZE),
    )

    companion object {
        /** Inbound samples arriving faster than this (per share) are dropped. */
        const val MIN_SAMPLE_INTERVAL_MS = 3_000L
        const val RETENTION_WINDOW_MS = 24L * 60 * 60_000L
        const val MAX_SAMPLES_PER_SHARE = 500
        private const val STOP_NOTIFY_BUDGET_MS = 8_000L
        private const val RETENTION_INTERVAL_MS = 60L * 60_000L
        private const val MAX_SHARE_ID_LENGTH = 64
        private const val MAX_LATITUDE = 90.0
        private const val MAX_LONGITUDE = 180.0
        private const val X25519_KEY_SIZE = 32
    }
}
