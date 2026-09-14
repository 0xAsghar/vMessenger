package ir.vmessenger.data.network

import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.PeerRelayService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envelope kinds [IncomingMessageCollector] hands to specialised services once
 * the sender policy passed. Behind an interface so the collector's policy,
 * receipt, dedup and notification logic is unit-testable with fakes instead
 * of the Android-bound services.
 */
interface InboundRoutes {
    fun start()

    /** Stops the background work the routed services run (prune/retention timers, location fan-out). */
    fun stop()

    /** Returns true when the transfer was already delivered and only needs a fresh receipt. */
    suspend fun attachmentInfo(contactId: String, envelope: MessageEnvelope): Boolean

    /** Returns the completed attachment when this chunk finished the transfer. */
    suspend fun attachmentChunk(contactId: String, envelope: MessageEnvelope): CompletedAttachment?

    suspend fun location(contactId: String, envelope: MessageEnvelope)

    suspend fun control(contactId: String, envelope: MessageEnvelope)

    /** An edit or a delete-for-everyone aimed at a message this peer sent us. */
    suspend fun messageRevision(contactId: String, envelope: MessageEnvelope)

    /** A peer's new display name or avatar. */
    suspend fun profileUpdate(contactId: String, envelope: MessageEnvelope)

    /** Network hints, mailbox and peer-relay traffic; false when the envelope kind is unknown. */
    suspend fun infrastructure(incoming: IncomingEnvelope): Boolean
}

@Singleton
@Suppress("LongParameterList") // one dependency per envelope family the collector fans out to
class DefaultInboundRoutes @Inject constructor(
    private val attachmentReceiver: AttachmentReceiver,
    private val locationSharingCoordinator: LocationSharingCoordinator,
    private val peerExchangeService: PeerExchangeService,
    private val mailboxProtocolService: MailboxProtocolService,
    private val mailboxSyncService: MailboxSyncService,
    private val peerRelayForwarder: PeerRelayForwarder,
    private val peerRelayService: PeerRelayService,
    private val messageRevisionHandler: MessageRevisionHandler,
    private val profileUpdateHandler: ProfileUpdateHandler,
) : InboundRoutes {
    override fun start() {
        locationSharingCoordinator.start()
        attachmentReceiver.start()
    }

    override fun stop() {
        attachmentReceiver.stop()
        locationSharingCoordinator.stop()
    }

    override suspend fun attachmentInfo(contactId: String, envelope: MessageEnvelope): Boolean =
        attachmentReceiver.handleInfo(contactId, envelope)

    override suspend fun attachmentChunk(contactId: String, envelope: MessageEnvelope): CompletedAttachment? =
        attachmentReceiver.handleChunk(contactId, envelope)

    override suspend fun location(contactId: String, envelope: MessageEnvelope) =
        locationSharingCoordinator.handleIncomingLocation(contactId, envelope)

    override suspend fun control(contactId: String, envelope: MessageEnvelope) =
        locationSharingCoordinator.handleIncomingControl(contactId, envelope)

    override suspend fun messageRevision(contactId: String, envelope: MessageEnvelope) =
        messageRevisionHandler.handle(contactId, envelope)

    override suspend fun profileUpdate(contactId: String, envelope: MessageEnvelope) =
        profileUpdateHandler.handle(contactId, envelope)

    override suspend fun infrastructure(incoming: IncomingEnvelope): Boolean {
        val envelope = incoming.envelope
        when {
            envelope.hasNetworkNodes() -> peerExchangeService.ingestFromEnvelope(incoming)
            // A blob pushed straight to us is only ever addressed to us; never stored for others.
            envelope.hasMailboxBlob() -> mailboxSyncService.deliverLocalBlob(envelope.mailboxBlob)
            envelope.hasRelayOpen() -> peerRelayForwarder.handleOpen(incoming)
            envelope.hasRelayData() -> peerRelayForwarder.handleData(incoming)
            envelope.hasRelayClose() -> peerRelayService.handleRelayClose(envelope)
            envelope.hasMailboxPut() ||
                envelope.hasMailboxList() ||
                envelope.hasMailboxFetch() ||
                envelope.hasMailboxDelete() ->
                mailboxProtocolService.handleIncoming(envelope, incoming.session)
            envelope.hasMailboxListResponse() ||
                envelope.hasMailboxFetchResponse() ->
                mailboxSyncService.handleResponse(envelope, incoming.session)
            else -> return false
        }
        return true
    }
}
