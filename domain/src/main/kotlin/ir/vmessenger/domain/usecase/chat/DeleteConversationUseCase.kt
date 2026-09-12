package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/** Deletes a conversation with its messages, queued sends and saved draft. */
class DeleteConversationUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(conversationId: String) =
        conversationRepository.deleteConversation(conversationId)
}
