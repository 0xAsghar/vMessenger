package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.domain.model.MessageDeliveryInfo
import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Everything knowable about one message: when it was composed, sent, delivered and read, how big
 * it is, and — for a group message we sent — who has it, one member at a time. The bubble's single
 * tick is their aggregate; this is the only place the per-member answer is visible.
 *
 * Null when the message is gone, which a sheet opened over a row that has just been deleted can hit.
 */
class ObserveDeliveryInfoUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(messageId: String): MessageDeliveryInfo? =
        conversationRepository.deliveryInfo(messageId)
}
