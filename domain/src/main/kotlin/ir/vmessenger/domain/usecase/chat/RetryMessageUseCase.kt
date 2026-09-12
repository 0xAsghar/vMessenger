package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/** Re-queues a message the user saw fail and wakes the dispatcher. */
class RetryMessageUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(messageId: String) = conversationRepository.retry(messageId)
}
