package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.encoding.IdentityHashMatcher
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.proto.app.v1.ContactResponseType
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeIdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContactRequestHandlerTest {
    private val self = InboundFixtures.identity(0x01)
    private val peer = InboundFixtures.peer(0x0A)

    private lateinit var contactDao: FakeContactDao
    private lateinit var requests: FakeContactRequestRepository
    private lateinit var handler: ContactRequestHandler

    @Before
    fun setUp() {
        val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        val identityRepository = FakeIdentityRepository(cryptoEngine)
        identityRepository.identity = self
        identityRepository.ed25519Private = ByteArray(64) { 0x11 }
        identityRepository.x25519StaticPrivate = ByteArray(32) { 0x12 }
        contactDao = FakeContactDao()
        requests = FakeContactRequestRepository()
        handler = ContactRequestHandler(
            contactRequestRepository = requests,
            contactRepository = FakeContactRepository(contactDao),
            contactRequestNotifier = ContactRequestNotifier(),
            contactRequestService = ContactRequestService(
                identityRepository,
                SelfIdentityCache(identityRepository, cryptoEngine),
                FakeMessagingPort(),
                ContactRequestRetryBudget(ContactRequestRetryStore.Transient),
                Dispatchers.Unconfined,
            ),
            contactDao = contactDao,
            identityRepository = identityRepository,
        )
    }

    @Test
    fun requestUserHashDerivedFromSessionPeerNotPayload() = runTest {
        // A stranger claims a trusted contact's user hash; the card must show the hash of the key they proved.
        val victim = InboundFixtures.peer(0x0B)
        val claimedHash = UserHashEncoder.encode(victim.identityHash)

        handler.handleRequest(
            InboundFixtures.requestEnvelope(requestIdFrom(peer), userHash = claimedHash, displayName = ""),
            peer,
        )

        val saved = requests.saved.single()
        val provenHash = UserHashEncoder.encode(peer.identityHash)
        assertEquals(provenHash, saved.requesterUserHash)
        assertEquals("name falls back to the derived hash, not the claimed one", provenHash, saved.requesterDisplayName)
        assertArrayEquals(peer.identityHash, saved.requesterIdentityHash)
        assertArrayEquals(peer.ed25519PublicKey, saved.requesterEd25519PublicKey)
    }

    @Test
    fun requestWithForeignRequestIdIgnored() = runTest {
        // Ids are deterministic over public hashes, so a peer can compute another requester's pending id.
        val other = InboundFixtures.peer(0x0B)

        handler.handleRequest(InboundFixtures.requestEnvelope(requestIdFrom(other), displayName = "Eve"), peer)
        handler.handleRequest(InboundFixtures.requestEnvelope("cr-0000000000000000000000000000dead"), peer)

        assertTrue(requests.saved.isEmpty())
    }

    @Test
    fun requestWithRoutingPrefixIdAccepted() = runTest {
        // A peer that added us by user hash only knows our routing prefix and derives the id from it.
        val prefixId = ContactRequestService.deterministicRequestId(
            peer.identityHash,
            IdentityHashMatcher.routingHash(self.identityHash),
        )

        handler.handleRequest(InboundFixtures.requestEnvelope(prefixId, displayName = "Sara"), peer)

        assertEquals(prefixId, requests.saved.single().requestId)
        assertEquals("Sara", requests.saved.single().requesterDisplayName)
    }

    @Test
    fun acceptWithoutPendingOutIgnored() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peer, status = ContactRelationshipStatus.REJECTED)

        handler.handleResponse(
            "a",
            InboundFixtures.responseEnvelope(requestIdFor(peer), ContactResponseType.CONTACT_RESPONSE_ACCEPT),
            peer,
        )

        assertEquals(ContactRelationshipStatus.REJECTED, contactDao.getById("a")!!.relationshipStatus)
    }

    @Test
    fun acceptWithWrongRequestIdIgnored() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peer, status = ContactRelationshipStatus.PENDING_OUT)

        val bogusRequestId = "cr-00000000000000000000000000000000"

        handler.handleResponse(
            "a",
            InboundFixtures.responseEnvelope(bogusRequestId, ContactResponseType.CONTACT_RESPONSE_ACCEPT),
            peer,
        )

        assertEquals(ContactRelationshipStatus.PENDING_OUT, contactDao.getById("a")!!.relationshipStatus)
    }

    @Test
    fun acceptUsesSessionPeerKeysNotPayload() = runTest {
        // Hash-added contact: routing prefix only, placeholder keys, request id derived from the prefix.
        val partialHash = IdentityHashMatcher.routingHash(peer.identityHash)
        val pending = InboundFixtures.contact("a", peer, status = ContactRelationshipStatus.PENDING_OUT).copy(
            identityHash = partialHash,
            ed25519Public = ByteArray(32),
            x25519StaticPublic = null,
            userHash = UserHashEncoder.encode(partialHash),
            displayName = UserHashEncoder.encode(partialHash),
        )
        contactDao.contacts += pending
        val attackerPub = ByteArray(32) { 0x66 }

        handler.handleResponse(
            "a",
            InboundFixtures.responseEnvelope(
                requestId = ContactRequestService.deterministicRequestId(self.identityHash, partialHash),
                type = ContactResponseType.CONTACT_RESPONSE_ACCEPT,
                responderIdentityPub = attackerPub,
                displayName = "Sara",
            ),
            peer,
        )

        val updated = contactDao.getById("a")!!
        assertEquals(ContactRelationshipStatus.APPROVED, updated.relationshipStatus)
        assertArrayEquals(peer.ed25519PublicKey, updated.ed25519Public)
        assertArrayEquals(peer.identityHash, updated.identityHash)
        assertArrayEquals(peer.x25519StaticPublicKey, updated.x25519StaticPublic)
        assertEquals(UserHashEncoder.encode(peer.identityHash), updated.userHash)
        assertEquals("Sara", updated.displayName)
    }

    @Test
    fun acceptFromDifferentSessionPeerIgnored() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peer, status = ContactRelationshipStatus.PENDING_OUT)
        val other = InboundFixtures.peer(0x0B)

        handler.handleResponse(
            "a",
            InboundFixtures.responseEnvelope(requestIdFor(peer), ContactResponseType.CONTACT_RESPONSE_ACCEPT),
            other,
        )

        val unchanged = contactDao.getById("a")!!
        assertEquals(ContactRelationshipStatus.PENDING_OUT, unchanged.relationshipStatus)
        assertArrayEquals(peer.ed25519PublicKey, unchanged.ed25519Public)
    }

    @Test
    fun revokeSetsRejected() = runTest {
        contactDao.contacts += InboundFixtures.contact("a", peer, status = ContactRelationshipStatus.APPROVED)

        handler.handleResponse(
            "a",
            InboundFixtures.responseEnvelope(requestIdFor(peer), ContactResponseType.CONTACT_RESPONSE_REVOKE),
            peer,
        )

        assertEquals(ContactRelationshipStatus.REJECTED, contactDao.getById("a")!!.relationshipStatus)
    }

    private fun requestIdFor(target: ir.vmessenger.network.messaging.PeerIdentity): String =
        ContactRequestService.deterministicRequestId(self.identityHash, target.identityHash)

    /** The id [requester] derives when filing a request with us. */
    private fun requestIdFrom(requester: ir.vmessenger.network.messaging.PeerIdentity): String =
        ContactRequestService.deterministicRequestId(requester.identityHash, self.identityHash)
}
