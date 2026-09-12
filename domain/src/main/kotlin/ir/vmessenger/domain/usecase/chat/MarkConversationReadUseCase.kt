package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Clears the unread badge, marks incoming messages READ and — when the user
 * allows read receipts — tells the sender.
 */
class MarkConversationReadUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(conversationId: String) =
        conversationRepository.markConversationRead(conversationId)
}
