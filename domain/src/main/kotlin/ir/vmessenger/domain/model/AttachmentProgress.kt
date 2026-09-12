package ir.vmessenger.domain.model

/**
 * Live progress of one attachment transfer, keyed by message id in
 * [ir.vmessenger.domain.repository.ConversationRepository.observeAttachmentProgress].
 */
data class AttachmentProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val direction: MessageDirection,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}
