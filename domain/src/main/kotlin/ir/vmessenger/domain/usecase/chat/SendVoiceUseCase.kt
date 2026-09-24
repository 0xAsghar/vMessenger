package ir.vmessenger.domain.usecase.chat

import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Queues a recorded voice message. The recorder hands over a plaintext file in
 * the cache; storing it encrypts it and removes that copy, so nothing readable
 * outlives the recording.
 */
class SendVoiceUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(
        conversationId: String,
        filePath: String,
        durationMs: Long,
        waveform: ByteArray,
        expiresAtUnixMs: Long? = null,
    ): AppResult<String> =
        conversationRepository.sendVoice(conversationId, filePath, durationMs, waveform, expiresAtUnixMs)
}
