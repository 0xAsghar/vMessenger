package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/** Persists unsent composer text; blank [text] deletes the draft. */
class SaveDraftUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(conversationId: String, text: String) =
        conversationRepository.saveDraft(conversationId, text)
}
