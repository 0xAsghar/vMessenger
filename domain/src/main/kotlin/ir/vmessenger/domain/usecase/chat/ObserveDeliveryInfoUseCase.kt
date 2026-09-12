package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.model.RecipientDelivery
import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Who has this message, one member at a time. The bubble's single tick is their aggregate;
 * this is the only place the per-member answer is visible.
 */
class ObserveDeliveryInfoUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(messageId: String): List<RecipientDelivery> =
        conversationRepository.deliveryInfo(messageId)
}
