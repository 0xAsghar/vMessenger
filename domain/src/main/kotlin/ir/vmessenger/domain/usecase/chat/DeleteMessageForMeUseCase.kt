package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/** Removes the local copy of a message only; the peer keeps theirs. */
class DeleteMessageForMeUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(messageId: String) = conversationRepository.deleteMessageForMe(messageId)
}
