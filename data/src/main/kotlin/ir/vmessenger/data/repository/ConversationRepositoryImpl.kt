package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.data.attachment.AttachmentStore
import ir.vmessenger.data.attachment.AttachmentTransferTracker
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Conversation
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import ir.vmessenger.core.database.entity.DeliveryStatus as DbDeliveryStatus
import ir.vmessenger.core.database.entity.MessageDirection as DbMessageDirection

@Singleton
// One collaborator per concern (DAOs, attachment store + tracker, read/write halves);
// one method per contract entry.
@Suppress("LongParameterList", "TooManyFunctions")
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val attachmentStore: AttachmentStore,
    private val transferTracker: AttachmentTransferTracker,
    private val readMarker: ConversationReadMarker,
    private val writer: ConversationWriter,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        combine(
            conversationDao.observeAllWithPreview(),
            contactDao.observeContacts(),
        ) { conversations, contacts ->
            val contactMap = contacts.associateBy { it.id }
            conversations.map { row ->
                val conv = row.conversation
                val contact = contactMap[conv.contactId]
                Conversation(
                    id = conv.id,
                    contactId = conv.contactId,
                    contactName = contact?.displayName ?: conv.contactId,
                    lastMessagePreview = row.lastMessagePreview,
                    lastActivityUnixMs = conv.lastActivityUnixMs,
                    unreadCount = conv.unreadCount,
                )
            }
        }

    override fun observeChatList(): Flow<List<ConversationSummary>> =
        conversationDao.observeChatList().map { rows -> rows.map { it.toSummary() } }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeConversation(conversationId).map { messages ->
            messages.map { it.toChatMessage() }
        }

    override fun observeMessages(conversationId: String, limit: Int): Flow<List<ChatMessage>> =
        messageDao.observeConversation(conversationId, limit).map { rows -> rows.map { it.toChatMessage() } }

    override suspend fun countMessages(conversationId: String): Int =
        messageDao.countForConversation(conversationId)

    override suspend fun indexOfMessage(conversationId: String, messageId: String): Int =
        messageDao.indexOf(conversationId, messageId)

    override suspend fun getOrCreateConversation(contactId: String): String {
        val existing = conversationDao.getByContactId(contactId)
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conversationDao.upsert(
            ConversationEntity(
                id = id,
                contactId = contactId,
                lastMessageId = null,
                lastActivityUnixMs = now,
                unreadCount = 0,
                muted = false,
            ),
        )
        return id
    }

    override suspend fun sendMessage(conversationId: String, text: String): AppResult<String> =
        sendMessage(conversationId, text, replyToMessageId = null)

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        replyToMessageId: String?,
    ): AppResult<String> = AppResult.Success(writer.sendText(conversationId, text, replyToMessageId))

    override suspend fun sendAttachment(conversationId: String, sourceUri: String): AppResult<String> =
        runCatching {
            val copied = attachmentStore.copyFromUri(sourceUri)
            val messageId = UUID.randomUUID().toString()
            writer.queue(
                MessageEntity(
                    messageId = messageId,
                    conversationId = conversationId,
                    direction = DbMessageDirection.OUTGOING,
                    contentType = copied.contentType,
                    body = null,
                    replyToMessageId = null,
                    status = DbDeliveryStatus.QUEUED,
                    createdAtUnixMs = System.currentTimeMillis(),
                    sentAtUnixMs = null,
                    deliveredAtUnixMs = null,
                    readAtUnixMs = null,
                    attachmentName = copied.fileName,
                    attachmentMimeType = copied.mimeType,
                    attachmentSizeBytes = copied.sizeBytes,
                    attachmentPath = copied.file.absolutePath,
                    attachmentSha256 = copied.sha256,
                    attachmentEncrypted = true,
                ),
            )
            AppLogger.info("Messaging", "outgoing attachment queued messageId=$messageId size=${copied.sizeBytes}")
            messageId
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppLogger.warn("Messaging", "attachment queue failed: ${it.message}")
                AppResult.Error(AppError.AttachmentFailed)
            },
        )

    override suspend fun markConversationRead(conversationId: String) = readMarker.markRead(conversationId)

    override suspend fun deleteMessageForMe(messageId: String) = writer.deleteMessageForMe(messageId)

    override suspend fun deleteConversation(conversationId: String) = writer.deleteConversation(conversationId)

    override suspend fun setMuted(conversationId: String, muted: Boolean) = writer.setMuted(conversationId, muted)

    override suspend fun retry(messageId: String) = writer.retry(messageId)

    override fun observeDraft(conversationId: String): Flow<String> = writer.observeDraft(conversationId)

    override suspend fun saveDraft(conversationId: String, text: String) = writer.saveDraft(conversationId, text)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAttachmentProgress(conversationId: String): Flow<Map<String, AttachmentProgress>> =
        flow { emit(conversationDao.getById(conversationId)?.contactId) }
            .flatMapLatest { contactId -> contactId?.let(transferTracker::forContact) ?: emptyFlow() }

    override suspend fun openAttachment(messageId: String): InputStream? {
        val path = messageDao.getById(messageId)?.attachmentPath ?: return null
        return runCatching { attachmentStore.openDecrypted(path) }
            .onFailure { AppLogger.warn("Messaging", "attachment open failed messageId=$messageId: ${it.message}") }
            .getOrNull()
    }

    override suspend fun exportAttachmentForViewing(messageId: String): AppResult<String> {
        val message = messageDao.getById(messageId)
        val path = message?.attachmentPath
            ?: return AppResult.Error(AppError.NotFound("attachment $messageId"))
        return runCatching { attachmentStore.exportForViewing(path, message.attachmentName.orEmpty()) }
            .fold(
                onSuccess = { AppResult.Success(it.absolutePath) },
                onFailure = {
                    AppLogger.warn("Messaging", "attachment export failed messageId=$messageId: ${it.message}")
                    AppResult.Error(AppError.AttachmentFailed)
                },
            )
    }
}
