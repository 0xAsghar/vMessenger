package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.model.ChatMessage
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * A window of the newest [limit] messages, newest first (index 0 is the newest,
 * which is what `reverseLayout = true` expects). Re-invoke with a bigger [limit]
 * to load earlier messages; [ir.vmessenger.domain.repository.ConversationRepository.countMessages]
 * says whether there are any.
 */
class ObserveMessagesPagedUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    operator fun invoke(conversationId: String, limit: Int): Flow<List<ChatMessage>> =
        conversationRepository.observeMessages(conversationId, limit)
}
