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
    override suspend fun onEstablished(session: ActiveSecureSession, self: PeerIdentity, peer: PeerIdentity) {
        peerExchangeService.exchangeOnSession(session, self)
        mailboxService.offerPending(session, self, peer.identityHash)
        mailboxSyncService.pullFromPeer(session, self)
    }
}
