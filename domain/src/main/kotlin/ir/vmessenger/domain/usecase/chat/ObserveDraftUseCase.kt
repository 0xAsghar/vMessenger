package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Unsent composer text of a conversation; empty when there is none. */
class ObserveDraftUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    operator fun invoke(conversationId: String): Flow<String> =
        conversationRepository.observeDraft(conversationId)
}
