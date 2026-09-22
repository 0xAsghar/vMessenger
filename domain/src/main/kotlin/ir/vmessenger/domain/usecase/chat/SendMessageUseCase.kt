package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/** Queues a text message; the outbox dispatcher owns delivery and retries. */
class SendMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(
        conversationId: String,
        text: String,
        replyToMessageId: String? = null,
        expiresAtUnixMs: Long? = null,
    ): AppResult<String> =
        conversationRepository.sendMessage(conversationId, text, replyToMessageId, expiresAtUnixMs)
}
