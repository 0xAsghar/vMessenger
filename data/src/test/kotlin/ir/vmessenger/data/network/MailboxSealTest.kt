package ir.vmessenger.data.network

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import dagger.Lazy
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.common.network.P2PConfig
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.KeyPair
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.database.entity.ContactEntity
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.notifications.ActiveConversationTracker
import ir.vmessenger.core.proto.app.v1.MailboxInner
import ir.vmessenger.core.proto.app.v1.MessageEnvelope
import ir.vmessenger.data.repository.FakeIdentityRepository
import ir.vmessenger.domain.model.Identity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sealed-box layer: only the recipient's static pair opens a blob, the
 * sender's identity signature must verify for the addressed recipient, and a
 * delivered blob reaches the collector as a normal incoming chat.
 */
class MailboxSealTest {
    private val cryptoEngine: CryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val seal = MailboxSeal(cryptoEngine)

    private lateinit var sender: KeyPair
    private lateinit var recipientStatic: KeyPair
    private lateinit var recipientHash: ByteArray
    private var flagBefore = false

    @Before
    fun setUp() {
        flagBefore = P2PConfig.storeAndForwardEnabled
        P2PConfig.storeAndForwardEnabled = true
        sender = cryptoEngine.generateEd25519KeyPair()
        recipientStatic = cryptoEngine.generateX25519KeyPair()
        recipientHash = cryptoEngine.randomBytes(32)
        ActiveConversationTracker.activeConversationId = null
    }

    @After
    fun tearDown() {
        P2PConfig.storeAndForwardEnabled = flagBefore
        ActiveConversationTracker.activeConversationId = null
    }

    @Test
    fun opensOnlyWithRecipientStaticKey() {
        val envelope = InboundFixtures.chatEnvelope("m1", "sealed hello")
        val sealed = sealFor(envelope)

        val opened = seal.open(sealed, recipientStatic.publicKey, recipientStatic.privateKey, listOf(recipientHash))
        assertNotNull(opened)
        assertEquals(envelope, opened!!.envelope)
        assertTrue(opened.senderIdentityPub.contentEquals(sender.publicKey))

        val other = cryptoEngine.generateX25519KeyPair()
        assertNull(seal.open(sealed, other.publicKey, other.privateKey, listOf(recipientHash)))
    }

    @Test
    fun senderSignatureRequired() {
        val envelope = InboundFixtures.chatEnvelope("m1", "forged")
        val envelopeBytes = envelope.toByteArray()

        // Signed by a key that is not the claimed sender identity.
        val impostor = cryptoEngine.generateEd25519KeyPair()
        val transcript = seal.transcript(recipientHash, envelopeBytes)
        val impostorSignature = cryptoEngine.signEd25519(transcript, impostor.privateKey)
        val forged = innerBox(envelopeBytes, impostorSignature)
        assertNull(seal.open(forged, recipientStatic.publicKey, recipientStatic.privateKey, listOf(recipientHash)))

        // No signature at all.
        val unsigned = innerBox(envelopeBytes, ByteArray(0))
        assertNull(seal.open(unsigned, recipientStatic.publicKey, recipientStatic.privateKey, listOf(recipientHash)))

        // Valid signature, but addressed to someone else: the recipient hash is bound.
        val sealed = sealFor(envelope)
        val someoneElse = cryptoEngine.randomBytes(32)
        assertNull(seal.open(sealed, recipientStatic.publicKey, recipientStatic.privateKey, listOf(someoneElse)))
    }

    @Test
    fun sealZeroizesSenderPrivateKey() {
        val privateCopy = sender.privateKey.copyOf()

        seal.seal(
            envelope = InboundFixtures.chatEnvelope("m1"),
            recipientIdentityHash = recipientHash,
            recipientStaticPublic = recipientStatic.publicKey,
            senderIdentityPub = sender.publicKey,
            senderEd25519Private = privateCopy,
        )

        assertTrue(privateCopy.all { it == 0.toByte() })
    }

    @Test
    fun deliveredEnvelopeReachesCollector() = runTest {
        val stack = DeliveryStack()
        stack.contactDao.contacts += stack.senderContact(sender.publicKey)
        val envelope = InboundFixtures.chatEnvelope("m1", "via mailbox")
        val blob = MailboxFixtures.blob(
            recipientHash = stack.identity.identityHash,
            sealedPayload = seal.seal(
                envelope = envelope,
                recipientIdentityHash = stack.identity.identityHash,
                recipientStaticPublic = stack.identity.x25519StaticPublicKey,
                senderIdentityPub = sender.publicKey,
                senderEd25519Private = sender.privateKey.copyOf(),
            ),
        )

        assertTrue(stack.sync.deliverLocalBlob(blob))

        val stored = stack.messageDao.getById("m1")
        assertNotNull(stored)
        assertEquals(MessageDirection.INCOMING, stored!!.direction)
        assertEquals("via mailbox", stored.body)
        assertEquals("sender", stack.conversationDao.getByContactId("sender")?.contactId)
        assertEquals(1, stack.messaging.receiptsFor("m1").size)
    }

    @Test
    fun blobFromStrangerOrBlockedSenderDropped() = runTest {
        val stack = DeliveryStack()
        val envelope = InboundFixtures.chatEnvelope("m1", "unwanted")
        val blob = MailboxFixtures.blob(
            recipientHash = stack.identity.identityHash,
            sealedPayload = seal.seal(
                envelope = envelope,
                recipientIdentityHash = stack.identity.identityHash,
                recipientStaticPublic = stack.identity.x25519StaticPublicKey,
                senderIdentityPub = sender.publicKey,
                senderEd25519Private = sender.privateKey.copyOf(),
            ),
        )

        assertFalse(stack.sync.deliverLocalBlob(blob))
        stack.contactDao.contacts += stack.senderContact(sender.publicKey).copy(blocked = true)
        assertFalse(stack.sync.deliverLocalBlob(blob))

        assertNull(stack.messageDao.getById("m1"))
        assertTrue(stack.messaging.sent.isEmpty())
    }

    @Test
    fun blobAddressedElsewhereNotOpened() = runTest {
        val stack = DeliveryStack()
        stack.contactDao.contacts += stack.senderContact(sender.publicKey)
        val blob = MailboxFixtures.blob(
            recipientHash = recipientHash,
            sealedPayload = sealFor(InboundFixtures.chatEnvelope("m1")),
        )

        assertFalse(stack.sync.deliverLocalBlob(blob))
        assertNull(stack.messageDao.getById("m1"))
    }

    private fun sealFor(envelope: MessageEnvelope): ByteArray = seal.seal(
        envelope = envelope,
        recipientIdentityHash = recipientHash,
        recipientStaticPublic = recipientStatic.publicKey,
        senderIdentityPub = sender.publicKey,
        senderEd25519Private = sender.privateKey.copyOf(),
    )

    private fun innerBox(envelopeBytes: ByteArray, signature: ByteArray): ByteArray {
        val inner = MailboxInner.newBuilder()
            .setSenderIdentityPub(ByteString.copyFrom(sender.publicKey))
            .setEnvelope(ByteString.copyFrom(envelopeBytes))
            .setSignature(ByteString.copyFrom(signature))
            .build()
            .toByteArray()
        return cryptoEngine.sealedBoxSeal(inner, recipientStatic.publicKey)
    }

    /** A real identity with real static keys plus the collector wired up on fakes. */
    private inner class DeliveryStack {
        // A generated identity, not a fixture: opening a sealed blob needs the real
        // X25519 private key, which the placeholder fixtures do not carry.
        val identityRepository = FakeIdentityRepository(cryptoEngine)
            .also { runBlocking { it.generateIdentity("Me") } }
        val harness = InboundHarness(cryptoEngine = cryptoEngine, identityRepository = identityRepository)
        val identity: Identity = harness.self
        val contactDao = harness.contactDao
        val conversationDao = harness.conversationDao
        val messageDao = harness.messageDao
        val messaging = harness.messaging
        val sync: MailboxSyncService

        init {
            val mailboxDao = FakeMailboxDao()
            val mailboxService = MailboxService(mailboxDao, identityRepository, seal)
            val protocol = MailboxProtocolService(mailboxDao, mailboxService, contactDao)
            sync = MailboxSyncService(
                protocol,
                seal,
                identityRepository,
                contactDao,
                mailboxDao,
                Lazy { harness.collector },
            )
        }

        fun senderContact(senderPub: ByteArray): ContactEntity {
            val hash = UserHashEncoder.identityHashFromPublicKey(senderPub)
            return ContactEntity(
                id = "sender",
                identityHash = hash,
                ed25519Public = senderPub,
                x25519StaticPublic = cryptoEngine.generateX25519KeyPair().publicKey,
                userHash = UserHashEncoder.encode(hash),
                displayName = "Sender",
                verified = false,
                blocked = false,
                relationshipStatus = ContactRelationshipStatus.APPROVED,
                createdAtUnixMs = 0L,
                lastSeenUnixMs = null,
            )
        }
    }
}
