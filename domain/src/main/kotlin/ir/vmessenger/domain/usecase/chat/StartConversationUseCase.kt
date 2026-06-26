package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

class StartConversationUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(contactId: String): String =
        conversationRepository.getOrCreateConversation(contactId)
}
