package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The chat list, newest activity first, from a single JOIN query. */
class ObserveChatListUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    operator fun invoke(): Flow<List<ConversationSummary>> = conversationRepository.observeChatList()
}
