package ir.vmessenger.data.repository

import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.encoding.UserHashEncoder
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.crypto.backup.BackupBundleCodec
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.LocationAccessEntity
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.backup.v1.BackupContact
import ir.vmessenger.core.proto.backup.v1.BackupConversation
import ir.vmessenger.core.proto.backup.v1.BackupPayload
import ir.vmessenger.data.backup.BackgroundBackupCodec
import ir.vmessenger.data.backup.BackupPayloadExporter
import ir.vmessenger.data.backup.BackupRestoreWriter
import ir.vmessenger.data.backup.BackupStore
import ir.vmessenger.domain.model.NetworkNodeRole
import ir.vmessenger.domain.model.RestoreSummary
import ir.vmessenger.domain.repository.IdentityBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IdentityBackupRepositoryImplTest {
    private lateinit var cryptoEngine: CryptoEngine
    private lateinit var codec: BackupBundleCodec
    private lateinit var fixtures: BackupFixtures
    private val passphrase = "correct horse battery".toCharArray()

    /** One side of a backup: its fakes plus the repository wired on top of them. */
    private inner class Device {
        val identityRepository = FakeIdentityRepository(cryptoEngine)
        val contactDao = FakeContactDao()
        val conversationDao = FakeConversationDao()
        val messageDao = FakeMessageDao()
        val locationAccessDao = FakeLocationAccessDao()
        val nodeRepository = FakeNodeRepository()
        val repository: IdentityBackupRepository

        init {
            val store = BackupStore(contactDao, conversationDao, messageDao, locationAccessDao, nodeRepository)
            repository = IdentityBackupRepositoryImpl(
                identityRepository = identityRepository,
                cryptoEngine = cryptoEngine,
                codec = BackgroundBackupCodec(codec, Dispatchers.Unconfined),
                exporter = BackupPayloadExporter(store, appVersion = "test"),
                writer = BackupRestoreWriter(store),
                transactionRunner = PassThroughTransactionRunner,
            )
        }
    }

    @Before
    fun setUp() {
        cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
        codec = BackupBundleCodec(cryptoEngine)
        fixtures = BackupFixtures(cryptoEngine, codec, passphrase)
    }

    @Test
    fun roundTripRestoresIdentityAndContacts() = runTest {
        val source = device()
        source.identityRepository.generateIdentity("Ali")
        val contact = fixtures.approvedContact("c1", "Sara")
        source.contactDao.contacts += contact
        source.conversationDao.conversations += conversation("conv1", "c1", "m2", 2_000L, muted = true)
        source.messageDao.messages += fixtures.textMessage("m1", "conv1", MessageDirection.INCOMING, 1_000L)
        source.messageDao.messages += MessageEntity(
            messageId = "m2",
            conversationId = "conv1",
            direction = MessageDirection.OUTGOING,
            contentType = MessageContentType.IMAGE,
            body = null,
            replyToMessageId = "m1",
            status = DeliveryStatus.DELIVERED,
            createdAtUnixMs = 2_000L,
            sentAtUnixMs = 2_100L,
            deliveredAtUnixMs = 2_200L,
            readAtUnixMs = null,
            attachmentName = "photo.jpg",
            attachmentMimeType = "image/jpeg",
            attachmentSizeBytes = 1234L,
            attachmentPath = "/data/user/0/app/files/attachments/photo.jpg",
        )
        source.locationAccessDao.rows += LocationAccessEntity("c1", canSeeMyLocation = true, updatedAtUnixMs = 5L)
        source.nodeRepository.addNode("wss://relay.example/relay", NetworkNodeRole.RELAY)

        val bundle = (source.repository.exportBundle(passphrase) as AppResult.Success).data
        val info = (source.repository.inspectBundle(bundle) as AppResult.Success).data
        assertEquals(BackupBundleCodec.FORMAT_VERSION, info.version)

        val target = device()
        val summary = (target.repository.importBundle(bundle, passphrase) as AppResult.Success).data
        assertEquals(RestoreSummary(contacts = 1, conversations = 1, messages = 2), summary)

        assertEquals(source.identityRepository.identity, target.identityRepository.identity)
        assertArrayEquals(source.identityRepository.ed25519Private, target.identityRepository.ed25519Private)
        assertArrayEquals(
            source.identityRepository.x25519StaticPrivate,
            target.identityRepository.x25519StaticPrivate,
        )

        assertEquals(listOf(contact), target.contactDao.contacts)
        val conversation = target.conversationDao.conversations.single()
        assertEquals("conv1", conversation.id)
        assertEquals("c1", conversation.contactId)
        assertEquals("m2", conversation.lastMessageId)
        assertEquals(2_000L, conversation.lastActivityUnixMs)
        assertTrue(conversation.muted)
        val restoredAttachment = target.messageDao.getById("m2")!!
        assertEquals("photo.jpg", restoredAttachment.attachmentName)
        assertEquals("image/jpeg", restoredAttachment.attachmentMimeType)
        assertEquals(1234L, restoredAttachment.attachmentSizeBytes)
        assertNull(restoredAttachment.attachmentPath)
        assertEquals("m1", restoredAttachment.replyToMessageId)
        assertEquals(2_200L, restoredAttachment.deliveredAtUnixMs)
        assertEquals("hello", target.messageDao.getById("m1")!!.body)
        assertEquals(listOf("c1"), target.locationAccessDao.grantedContactIds())
        assertEquals("wss://relay.example/relay", target.nodeRepository.nodes.single().address)
        assertEquals(NetworkNodeRole.RELAY, target.nodeRepository.nodes.single().role)
    }

    @Test
    fun importRefusedWhenIdentityExists() = runTest {
        val target = device()
        target.identityRepository.generateIdentity("Existing")
        val existing = target.identityRepository.identity
        val payload = fixtures.payloadWith(contacts = listOf(fixtures.approvedContact("c1", "Sara").toBackup()))

        val result = target.repository.importBundle(fixtures.fastBundle(payload), passphrase)

        assertTrue(result is AppResult.Error && result.error is AppError.Validation)
        assertEquals(existing, target.identityRepository.identity)
        assertTrue(target.contactDao.contacts.isEmpty())
    }

    @Test
    fun identityKeyPairMismatchRejected() = runTest {
        val target = device()
        val otherX25519 = cryptoEngine.generateX25519KeyPair()
        val mismatched = fixtures.backupIdentity("Ali").toBuilder()
            .setX25519StaticPublic(ByteString.copyFrom(otherX25519.publicKey))
            .build()
        val contacts = listOf(fixtures.approvedContact("c1", "Sara").toBackup())
        val payload = fixtures.payloadWith(identity = mismatched, contacts = contacts)

        val result = target.repository.importBundle(fixtures.fastBundle(payload), passphrase)

        assertTrue(result is AppResult.Error && result.error is AppError.Validation)
        assertNull(target.identityRepository.identity)
        assertTrue(target.contactDao.contacts.isEmpty())
    }

    @Test
    fun userHashRecomputedOnImport() = runTest {
        val target = device()
        val contact = fixtures.approvedContact("c1", "Sara")
        val payload = fixtures.payloadWith(contacts = listOf(contact.toBackup()))
        val serialized = String(payload.toByteArray(), Charsets.ISO_8859_1)
        assertFalse("user hash must not be stored in the payload", serialized.contains(contact.userHash))

        val result = target.repository.importBundle(fixtures.fastBundle(payload), passphrase)

        assertTrue(result is AppResult.Success)
        val restored = target.contactDao.contacts.single()
        assertEquals(UserHashEncoder.encode(contact.identityHash), restored.userHash)
        val identity = target.identityRepository.identity!!
        val expectedHash = UserHashEncoder.identityHashFromPublicKey(identity.ed25519PublicKey)
        assertArrayEquals(expectedHash, identity.identityHash)
        assertEquals(UserHashEncoder.encode(expectedHash), identity.userHash)
    }

    @Test
    fun hashAddedContactsRestoreAsPendingOut() = runTest {
        val target = device()
        val partialHash = ByteArray(32).also { cryptoEngine.randomBytes(16).copyInto(it) }
        val hashAdded = BackupContact.newBuilder()
            .setId("c-hash")
            .setIdentityHash(ByteString.copyFrom(partialHash))
            .setEd25519Public(ByteString.copyFrom(ByteArray(32)))
            .setDisplayName("Pending friend")
            .setVerified(true)
            .setRelationshipStatus(ContactRelationshipStatus.APPROVED.name)
            .setCreatedAtUnixMs(10L)
            .build()
        val payload = fixtures.payloadWith(contacts = listOf(hashAdded))

        val result = target.repository.importBundle(fixtures.fastBundle(payload), passphrase)

        assertTrue(result is AppResult.Success)
        val restored = target.contactDao.contacts.single()
        assertEquals("c-hash", restored.id)
        assertEquals(ContactRelationshipStatus.PENDING_OUT, restored.relationshipStatus)
        assertArrayEquals(ByteArray(32), restored.ed25519Public)
        assertArrayEquals(partialHash, restored.identityHash)
        assertNull(restored.x25519StaticPublic)
        assertFalse(restored.verified)
        assertEquals(UserHashEncoder.encode(partialHash), restored.userHash)
    }

    @Test
    fun existingConversationNotReplaced() = runTest {
        val target = device()
        val contact = fixtures.approvedContact("backup-c1", "Sara")
        target.contactDao.contacts += contact.copy(id = "local-c1")
        target.conversationDao.conversations += conversation("local-conv", "local-c1", "kept", 50L)
        target.messageDao.messages += fixtures.textMessage("kept", "local-conv", MessageDirection.INCOMING, 50L)
        target.conversationDao.upsertCalls = 0
        val conversation = BackupConversation.newBuilder()
            .setId("backup-conv")
            .setContactId("backup-c1")
            .setMuted(true)
            .addMessages(
                fixtures.textMessage("kept", "backup-conv", MessageDirection.INCOMING, 1L).toBackup("replaced?"),
            )
            .addMessages(
                fixtures.textMessage("new", "backup-conv", MessageDirection.OUTGOING, 60L).toBackup("new text"),
            )
            .build()
        val payload = fixtures.payloadWith(contacts = listOf(contact.toBackup()), conversations = listOf(conversation))

        val result = target.repository.importBundle(fixtures.fastBundle(payload), passphrase)

        assertEquals(RestoreSummary(contacts = 0, conversations = 0, messages = 1), (result as AppResult.Success).data)
        assertEquals(0, target.conversationDao.upsertCalls)
        assertEquals(listOf("local-conv"), target.conversationDao.conversations.map { it.id })
        val kept = target.messageDao.getById("kept")!!
        assertEquals("hello", kept.body)
        assertEquals("local-conv", kept.conversationId)
        val added = target.messageDao.getById("new")
        assertNotNull(added)
        assertEquals("local-conv", added!!.conversationId)
        assertEquals("new text", added.body)
    }

    /**
     * A group conversation has no contact (its contactId is null), and the backup format has no place for
     * groups: the export must leave it out rather than fail. Before 2.0.2 it threw inside protobuf's
     * setter, so any phone with a group chat could not make a backup at all. A message on a timer is
     * left out as well.
     */
    @Test
    fun exportSkipsGroupConversationsInsteadOfFailing() = runTest {
        val source = device()
        source.identityRepository.generateIdentity("Ali")
        source.contactDao.contacts += fixtures.approvedContact("c1", "Sara")
        source.conversationDao.conversations += conversation("conv1", "c1", "m1", 1_000L)
        source.conversationDao.conversations += ConversationEntity(
            id = "group-conv",
            contactId = null,
            groupId = "g1",
            lastMessageId = "gm1",
            lastActivityUnixMs = 2_000L,
            unreadCount = 0,
            muted = false,
        )
        source.messageDao.messages += fixtures.textMessage("m1", "conv1", MessageDirection.INCOMING, 1_000L)
        source.messageDao.messages += fixtures.textMessage("gm1", "group-conv", MessageDirection.INCOMING, 2_000L)
        // On a timer: the format has no deadline, so a restored copy would never expire.
        source.messageDao.messages += fixtures.textMessage("m2", "conv1", MessageDirection.INCOMING, 1_500L)
            .copy(expiresAtUnixMs = 9_000L)

        val export = source.repository.exportBundle(passphrase)
        assertTrue("export failed: $export", export is AppResult.Success)
        val payload = BackupPayload.parseFrom(codec.decode((export as AppResult.Success).data, passphrase))
        assertEquals(listOf("conv1"), payload.conversationsList.map { it.id })
        assertEquals(listOf("m1"), payload.conversationsList.flatMap { it.messagesList }.map { it.messageId })

        val target = device()
        val imported = target.repository.importBundle(export.data, passphrase)
        val summary = (imported as AppResult.Success).data
        assertEquals(RestoreSummary(contacts = 1, conversations = 1, messages = 1), summary)
        assertEquals("c1", target.conversationDao.conversations.single().contactId)
        assertNull(target.messageDao.getById("gm1"))
    }

    private fun device(): Device = Device()

    /** A 1:1 conversation row; `groupId` stays null, which is what makes it 1:1. */
    private fun conversation(
        id: String,
        contactId: String,
        lastMessageId: String,
        activityUnixMs: Long,
        muted: Boolean = false,
    ) = ConversationEntity(
        id = id,
        contactId = contactId,
        lastMessageId = lastMessageId,
        lastActivityUnixMs = activityUnixMs,
        unreadCount = 0,
        muted = muted,
    )
}
