package ir.vmessenger.data.repository

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.core.proto.app.v1.ReceiptType
import ir.vmessenger.data.network.FakeMessagingPort
import ir.vmessenger.data.network.InboundFixtures
import ir.vmessenger.data.network.ReceiptSender
import ir.vmessenger.data.network.SelfIdentityCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The read-state path of `ConversationRepositoryImpl`, which delegates
 * verbatim to [ConversationReadMarker] (the repository's other collaborators —
 * outbox dispatcher, attachment store — need a real Android context).
 */
class ConversationRepositoryImplTest {
    private val cryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava()))
    private val peer = InboundFixtures.peer(0x0A)

    private lateinit var messageDao: FakeMessageDao
    private lateinit var conversationDao: FakeConversationDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var messaging: FakeMessagingPort
    private lateinit var receiptSender: ReceiptSender
    private lateinit var canceller: RecordingNotificationCanceller
    private var receiptsAllowed = true

    private class RecordingNotificationCanceller : ConversationNotificationCanceller {
        val cancelled = mutableListOf<String>()

        override fun cancel(conversationId: String) {
            cancelled += conversationId
        }
    }

    @Before
    fun setUp() {
        val identityRepository = FakeIdentityRepository(cryptoEngine)
        InboundFixtures.installIdentity(identityRepository, 0x01)
        contactDao = FakeContactDao().apply { contacts += InboundFixtures.contact(CONTACT_ID, peer) }
        messageDao = FakeMessageDao()
        conversationDao = FakeConversationDao()
        messaging = FakeMessagingPort()
        canceller = RecordingNotificationCanceller()
        receiptSender = ReceiptSender(
            messaging = messaging,
            selfIdentityCache = SelfIdentityCache(identityRepository, cryptoEngine),
            contactDao = contactDao,
            ioDispatcher = Dispatchers.Unconfined,
        )
        receiptsAllowed = true
    }

    private fun marker() = ConversationReadMarker(
        messageDao = messageDao,
        conversationDao = conversationDao,
        contactDao = contactDao,
        receiptSender = receiptSender,
        readReceiptPolicy = object : ReadReceiptPolicy {
            override suspend fun readReceiptsEnabled(): Boolean = receiptsAllowed
        },
        notificationCanceller = canceller,
    )

    private suspend fun seedUnread(count: Int) {
        conversationDao.upsert(
            ConversationEntity(
                id = CONVERSATION_ID,
                contactId = CONTACT_ID,
                lastMessageId = null,
                lastActivityUnixMs = 0L,
                unreadCount = count,
                muted = false,
            ),
        )
        repeat(count) { index ->
            messageDao.insert(incoming("m$index"))
        }
        // An outgoing message must never end up in a READ receipt.
        messageDao.insert(incoming("out").copy(messageId = "out", direction = MessageDirection.OUTGOING))
    }

    private fun incoming(id: String) = MessageEntity(
        messageId = id,
        conversationId = CONVERSATION_ID,
        direction = MessageDirection.INCOMING,
        contentType = MessageContentType.TEXT,
        body = "سلام",
        replyToMessageId = null,
        status = DeliveryStatus.DELIVERED,
        createdAtUnixMs = 1_000L,
        sentAtUnixMs = null,
        deliveredAtUnixMs = null,
        readAtUnixMs = null,
    )

    @Test
    fun markReadResetsUnreadAndQueuesReadReceipts() = runTest {
        seedUnread(2)
        receiptSender.start()

        marker().markRead(CONVERSATION_ID)

        assertEquals(0, conversationDao.getById(CONVERSATION_ID)?.unreadCount)
        assertTrue(messageDao.selectUnreadIncomingIds(CONVERSATION_ID).isEmpty())
        assertEquals(listOf(CONVERSATION_ID), canceller.cancelled)

        val receipt = messaging.sent.single().second.receipt
        assertEquals(ReceiptType.RECEIPT_TYPE_READ, receipt.type)
        val refs = listOf(receipt.refMessageId.toStringUtf8()) + receipt.refMessageIdsList.map { it.toStringUtf8() }
        assertEquals(listOf("m0", "m1"), refs)
    }

    @Test
    fun readReceiptsRespectPrivacyToggle() = runTest {
        seedUnread(2)
        receiptSender.start()
        receiptsAllowed = false

        marker().markRead(CONVERSATION_ID)

        // Local state still clears; only the peer is kept in the dark.
        assertEquals(0, conversationDao.getById(CONVERSATION_ID)?.unreadCount)
        assertTrue(messageDao.selectUnreadIncomingIds(CONVERSATION_ID).isEmpty())
        assertEquals(listOf(CONVERSATION_ID), canceller.cancelled)
        assertTrue(messaging.sent.isEmpty())
        assertTrue(messaging.sentOnExistingSession.isEmpty())
    }

    @Test
    fun nothingUnreadSendsNoReceipt() = runTest {
        seedUnread(0)
        receiptSender.start()

        marker().markRead(CONVERSATION_ID)

        assertEquals(listOf(CONVERSATION_ID), canceller.cancelled)
        assertTrue(messaging.sent.isEmpty())
    }

    private companion object {
        const val CONVERSATION_ID = "conv-a"
        const val CONTACT_ID = "a"
    }
}
