package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

class MuteConversationUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(conversationId: String, muted: Boolean) =
        conversationRepository.setMuted(conversationId, muted)
}
