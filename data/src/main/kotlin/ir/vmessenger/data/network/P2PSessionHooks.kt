package ir.vmessenger.data.network

import ir.vmessenger.network.messaging.ActiveSecureSession
import ir.vmessenger.network.messaging.PeerIdentity
import ir.vmessenger.network.messaging.SessionPostHandshakeHandler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class P2PSessionHooks @Inject constructor(
    private val peerExchangeService: PeerExchangeService,
    private val mailboxService: MailboxService,
    private val mailboxSyncService: MailboxSyncService,
) : SessionPostHandshakeHandler {
    /**
     * Everything a fresh session is worth doing once, in the order that matters.
     *
     * Hand over what we are holding *for this peer* first — that is the message they came for.
     * Then ask what they are holding for us. Then, last, ask them to hold something for someone
     * else: that is the only one of the three that spends the peer's storage rather than ours, so
     * it goes after the work they actually benefit from.
     */
    override suspend fun onEstablished(session: ActiveSecureSession, self: PeerIdentity, peer: PeerIdentity) {
        peerExchangeService.exchangeOnSession(session, self)
        mailboxService.offerPending(session, self, peer.identityHash)
        mailboxSyncService.pullFromPeer(session, self)
        mailboxSyncService.pushPendingToHost(session, self)
    }
}
