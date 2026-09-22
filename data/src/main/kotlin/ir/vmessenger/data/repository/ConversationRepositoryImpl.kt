package ir.vmessenger.data.repository

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.core.common.logging.AppLogger
import ir.vmessenger.core.database.dao.ContactDao
import ir.vmessenger.core.database.dao.ConversationDao
import ir.vmessenger.core.database.dao.GroupDao
import ir.vmessenger.core.database.dao.MessageDao
import ir.vmessenger.core.database.dao.MessageRecipientDao
import ir.vmessenger.core.database.entity.ConversationEntity
import ir.vmessenger.core.database.entity.MessageEntity
import ir.vmessenger.data.attachment.AttachmentStore
import ir.vmessenger.data.attachment.AttachmentTransferTracker
import ir.vmessenger.data.attachment.CopiedAttachment
import ir.vmessenger.data.network.MessageRevisionSender
import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.model.Conversation
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.model.MessageDeliveryInfo
import ir.vmessenger.domain.model.RecipientDelivery
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.File
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
    private val groupDao: GroupDao,
    private val recipientDao: MessageRecipientDao,
    private val attachmentStore: AttachmentStore,
    private val transferTracker: AttachmentTransferTracker,
    private val readMarker: ConversationReadMarker,
    private val writer: ConversationWriter,
    private val revisions: MessageRevisionSender,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<Conversation>> =
        combine(
            conversationDao.observeAllWithPreview(),
            contactDao.observeContacts(),
        ) { conversations, contacts ->
            val contactMap = contacts.associateBy { it.id }
            conversations.map { row ->
                val conv = row.conversation
                val contact = conv.contactId?.let(contactMap::get)
                Conversation(
                    id = conv.id,
                    contactId = conv.contactId,
                    contactName = contact?.displayName ?: conv.contactId ?: conv.groupId.orEmpty(),
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
        expiresAtUnixMs: Long?,
    ): AppResult<String> = writer.sendText(conversationId, text, replyToMessageId, expiresAtUnixMs)

    override suspend fun sendAttachment(conversationId: String, sourceUri: String): AppResult<String> =
        queueAttachment(conversationId) { attachmentStore.copyFromUri(sourceUri) }

    override suspend fun sendVoice(
        conversationId: String,
        filePath: String,
        durationMs: Long,
        waveform: ByteArray,
    ): AppResult<String> = queueAttachment(conversationId, durationMs, waveform) {
        // The recorder wrote plaintext into the cache; importing encrypts it into
        // app-private storage and deletes the temporary file.
        attachmentStore.importFile(File(filePath), VOICE_MIME_TYPE, voiceFileName())
    }

    /**
     * Stores an attachment and queues one delivery per recipient. [copy] runs the
     * encrypting import, which is the only step that can fail before the message
     * exists; everything after it is bookkeeping.
     */
    private suspend fun queueAttachment(
        conversationId: String,
        durationMs: Long? = null,
        waveform: ByteArray? = null,
        copy: suspend () -> CopiedAttachment,
    ): AppResult<String> {
        val copied = runCatching { copy() }.getOrElse {
            AppLogger.warn("Messaging", "attachment queue failed: ${it.message}")
            return AppResult.Error(AppError.AttachmentFailed)
        }
        val messageId = UUID.randomUUID().toString()
        val result = writer.queue(
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
                attachmentDurationMs = durationMs,
                attachmentWaveform = waveform,
            ),
        )
        AppLogger.info("Messaging", "outgoing attachment queued messageId=$messageId size=${copied.sizeBytes}")
        return result
    }

    override suspend fun markConversationRead(conversationId: String) = readMarker.markRead(conversationId)

    override suspend fun markVoicePlayed(messageId: String) =
        messageDao.markVoicePlayed(messageId, System.currentTimeMillis())

    /**
     * Names come from the user's own contact row first and the group snapshot second — what
     * the user calls someone beats what that person calls themselves — and fall back to a
     * short hash prefix for a member we have neither for.
     */
    override suspend fun deliveryInfo(messageId: String): MessageDeliveryInfo? {
        val message = messageDao.getById(messageId) ?: return null
        val groupId = conversationDao.getById(message.conversationId)?.groupId
        val members = groupId?.let { groupDao.activeMembers(it) }.orEmpty().associateBy { it.identityHash }
        val recipients = recipientDao.forMessage(messageId).map { row ->
            RecipientDelivery(
                identityHash = row.identityHash,
                displayName = contactDao.getByRoutingKey(row.identityHash)?.displayName?.ifBlank { null }
                    ?: members[row.identityHash]?.displayName?.ifBlank { null }
                    ?: row.identityHash.take(HASH_PREFIX_CHARS),
                status = row.status.toDomain(),
                sentAtUnixMs = row.sentAtUnixMs,
                deliveredAtUnixMs = row.deliveredAtUnixMs,
                readAtUnixMs = row.readAtUnixMs,
            )
        }
        return MessageDeliveryInfo(
            outgoing = message.direction == DbMessageDirection.OUTGOING,
            sentAtUnixMs = message.sentAtUnixMs,
            deliveredAtUnixMs = message.deliveredAtUnixMs,
            readAtUnixMs = message.readAtUnixMs,
            editedAtUnixMs = message.editedAtUnixMs,
            deletedAtUnixMs = message.deletedAtUnixMs,
            // No column stores a text message's size, and inventing one would be a migration for
            // a label; the body's own UTF-8 length is the honest answer for a text bubble.
            sizeBytes = message.attachmentSizeBytes ?: message.body?.toByteArray()?.size?.toLong(),
            recipients = recipients,
        )
    }

    override suspend fun deleteMessageForMe(messageId: String) = writer.deleteMessageForMe(messageId)

    override suspend fun editMessage(messageId: String, newText: String): AppResult<Unit> =
        revisions.edit(messageId, newText)

    override suspend fun deleteMessageForEveryone(messageId: String): AppResult<Unit> =
        revisions.deleteForEveryone(messageId)

    override suspend fun deleteConversation(conversationId: String) = writer.deleteConversation(conversationId)

    override suspend fun setMuted(conversationId: String, muted: Boolean) = writer.setMuted(conversationId, muted)

    override suspend fun retry(messageId: String) = writer.retry(messageId)

    override fun observeDraft(conversationId: String): Flow<String> = writer.observeDraft(conversationId)

    override suspend fun saveDraft(conversationId: String, text: String) = writer.saveDraft(conversationId, text)

    /**
     * A group transfer runs once per member, so progress is the union of the per-contact
     * trackers of everyone the message is going to.
     *
     * It must emit even when there is nobody to track. The conversation screen `combine`s this
     * with everything else it renders, and a flow that never emits holds the whole screen at
     * its initial state — which is how a group whose last other member left came out blank,
     * with no title, no messages and no error.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAttachmentProgress(conversationId: String): Flow<Map<String, AttachmentProgress>> =
        flow { emit(progressContacts(conversationId)) }
            .flatMapLatest { contactIds -> transferTracker.forContacts(contactIds) }

    private suspend fun progressContacts(conversationId: String): Set<String> {
        val conversation = conversationDao.getById(conversationId)
        val groupId = conversation?.groupId
        return when {
            conversation == null -> emptySet()
            groupId == null -> setOfNotNull(conversation.contactId)
            else -> groupDao.activeMembers(groupId)
                .mapNotNull { contactDao.getByRoutingKey(it.identityHash)?.id }
                .toSet()
        }
    }

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

    private companion object {
        /** AAC in an MP4 container: what [ir.vmessenger.domain.repository.ConversationRepository.sendVoice] records. */
        const val VOICE_MIME_TYPE = "audio/mp4"

        fun voiceFileName(): String = "voice-${System.currentTimeMillis()}.m4a"

        /** Enough of a routing key to tell two unknown members apart, without being a wall of hex. */
        const val HASH_PREFIX_CHARS = 8
    }
}
