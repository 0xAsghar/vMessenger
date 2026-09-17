package ir.vmessenger.data.network

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import ir.vmessenger.core.crypto.CryptoEngine
import ir.vmessenger.core.crypto.LazysodiumCryptoEngine
import ir.vmessenger.data.repository.ConversationWriter
import ir.vmessenger.data.repository.FakeContactDao
import ir.vmessenger.data.repository.FakeConversationDao
import ir.vmessenger.data.repository.FakeConversationDraftStore
import ir.vmessenger.data.repository.FakeGroupDao
import ir.vmessenger.data.repository.FakeIdentityDao
import ir.vmessenger.data.repository.FakeIdentityRepository
import ir.vmessenger.data.repository.FakeMessageDao
import ir.vmessenger.data.repository.FakeMessageRecipientDao
import ir.vmessenger.data.repository.GroupFixtures
import ir.vmessenger.data.repository.MessageRecipientResolver
import ir.vmessenger.data.repository.RecordingOutboxWaker
import ir.vmessenger.domain.model.Identity
import kotlinx.coroutines.Dispatchers

/**
 * The whole inbound graph over in-memory DAOs: the collector plus everything it
 * delegates to (conversation resolution, receipts, group control, fan-out).
 *
 * The DAO fakes share their backing lists the way the real tables share a
 * database, so a row written through one is visible to the joins of the others.
 */
class InboundHarness(
    selfSeed: Byte = 0x01,
    val cryptoEngine: CryptoEngine = LazysodiumCryptoEngine(LazySodiumJava(SodiumJava())),
    val identityRepository: FakeIdentityRepository = FakeIdentityRepository(cryptoEngine),
) {
    /** A caller that brought its own identity (real key material) keeps it. */
    val self: Identity = identityRepository.identity ?: InboundFixtures.installIdentity(identityRepository, selfSeed)
    val selfIdentityCache = SelfIdentityCache(identityRepository, cryptoEngine)

    val contactDao = FakeContactDao()
    val groupDao = FakeGroupDao()
    val outboxDao = FakeOutboxDao()
    val recipientDao = FakeMessageRecipientDao()
    val identityDao = FakeIdentityDao().apply { identity = GroupFixtures.identityRow(self) }
    val messageDao = FakeMessageDao(outboxDao.items, contactDao.contacts, groupDao.members)
    val conversationDao =
        FakeConversationDao(contactDao.contacts, messageDao.messages, groupDao.groups, groupDao.members)

    val messaging = FakeMessagingPort()
    val notifier = FakeIncomingMessageNotifier()
    val routes = FakeInboundRoutes()
    val draftStore = FakeConversationDraftStore()
    val attachmentFiles = FakeAttachmentFileStore()
    val waker = RecordingOutboxWaker()

    val deliveryAggregator = DeliveryAggregator(messageDao, recipientDao)
    val recipientResolver = MessageRecipientResolver(conversationDao, contactDao, groupDao, identityDao)
    val writer = ConversationWriter(
        conversationDao = conversationDao,
        messageDao = messageDao,
        outboxDao = outboxDao,
        recipientDao = recipientDao,
        recipientResolver = recipientResolver,
        draftStore = draftStore,
        attachmentFiles = attachmentFiles,
        outboxWaker = waker,
    )
    val conversationResolver = InboundConversationResolver(conversationDao, contactDao, groupDao)
    val revisionHandler = MessageRevisionHandler(messageDao, conversationDao, contactDao, attachmentFiles)
    val receiptHandler = InboundReceiptHandler(messageDao, contactDao, recipientDao, outboxDao, deliveryAggregator)
    val groupControlHandler = GroupControlHandler(
        groupDao = groupDao,
        conversationDao = conversationDao,
        contactDao = contactDao,
        writer = writer,
        controlSender = GroupControlSender(groupDao, contactDao, messaging, selfIdentityCache),
        selfIdentity = selfIdentityCache,
    )
    val receiptSender = ReceiptSender(messaging, selfIdentityCache, contactDao, Dispatchers.Unconfined)
        .also { it.start() }

    val collector = IncomingMessageCollector(
        messaging = messaging,
        contactDao = contactDao,
        conversationDao = conversationDao,
        messageDao = messageDao,
        contactRequestHandler = ContactRequestHandler(
            contactRequestRepository = FakeContactRequestRepository(),
            contactRepository = FakeContactRepository(contactDao),
            contactRequestNotifier = ContactRequestNotifier(),
            contactRequestService = ContactRequestService(
                identityRepository,
                selfIdentityCache,
                messaging,
                ContactRequestRetryBudget(ContactRequestRetryStore.Transient),
                Dispatchers.Unconfined,
            ),
            contactDao = contactDao,
            identityRepository = identityRepository,
        ),
        conversationResolver = conversationResolver,
        groupControlHandler = groupControlHandler,
        receiptHandler = receiptHandler,
        receiptSender = receiptSender,
        routes = routes,
        notifier = notifier,
        ioDispatcher = Dispatchers.Unconfined,
    )
}
