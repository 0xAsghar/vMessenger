package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/** Erases every message whose self-destruct timer has elapsed; driven by the periodic purge worker. */
class PurgeExpiredMessagesUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke() = conversationRepository.purgeExpiredMessages()
}
