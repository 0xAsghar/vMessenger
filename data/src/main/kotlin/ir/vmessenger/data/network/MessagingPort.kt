package ir.vmessenger.data.network

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.network.messaging.IncomingEnvelope
import ir.vmessenger.network.messaging.MessagingService
import ir.vmessenger.network.messaging.PeerIdentity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The slice of [MessagingService] the inbound handlers use: where authenticated
 * envelopes are delivered and a way to answer a peer (receipts, contact
 * responses). Behind an interface so those handlers are unit-testable
 * without a transport.
 */
interface MessagingPort {
    /**
     * Envelopes that arrived while no sink was installed. The live path is
     * [setIncomingSink]; this flow only exists to drain that backlog.
     */
    val incoming: Flow<IncomingEnvelope>

    /** Installs the consumer called from each session's read loop; `null` uninstalls it. */
    fun setIncomingSink(sink: (suspend (IncomingEnvelope) -> Unit)?)

    /**
     * [forceReconnect] dials a fresh session instead of reusing the open one. For a retry that is
     * waiting on an answer: a session whose peer vanished without closing it still accepts writes,
     * so reusing it reports "sent" for frames nobody will ever read.
     */
    suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelope: MessageEnvelope,
        forceReconnect: Boolean = false,
    ): AppResult<Unit>

    /** Writes on the already-open outbound session to [contactId] without dialing; false when there is none. */
    suspend fun sendOnExistingSession(contactId: String, envelope: MessageEnvelope): Boolean
}

@Singleton
class MessagingServicePort @Inject constructor(
    private val messagingService: MessagingService,
) : MessagingPort {
    override val incoming: Flow<IncomingEnvelope>
        get() = messagingService.incoming

    override fun setIncomingSink(sink: (suspend (IncomingEnvelope) -> Unit)?) =
        messagingService.setIncomingSink(sink)

    override suspend fun send(
        contactId: String,
        self: PeerIdentity,
        peer: PeerIdentity,
        envelope: MessageEnvelope,
        forceReconnect: Boolean,
    ): AppResult<Unit> = messagingService.send(contactId, self, peer, envelope, forceReconnect)

    override suspend fun sendOnExistingSession(contactId: String, envelope: MessageEnvelope): Boolean =
        messagingService.sendOnExistingSession(contactId, envelope)
}
