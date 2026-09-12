package ir.vmessenger.data.attachment

import ir.vmessenger.domain.model.AttachmentProgress
import ir.vmessenger.domain.model.MessageDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory progress of the attachment transfers currently in flight (both
 * directions), keyed by message id. Entries are removed when a transfer
 * completes or is abandoned; nothing here is persisted.
 */
@Singleton
class AttachmentTransferTracker @Inject constructor() {
    private class Entry(val contactId: String, val progress: AttachmentProgress)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val snapshot = MutableStateFlow<Map<String, AttachmentProgress>>(emptyMap())

    /** Every live transfer, keyed by message id. */
    val transfers: StateFlow<Map<String, AttachmentProgress>> = snapshot.asStateFlow()

    /** Transfers exchanged with one contact (the conversation's peer). */
    fun forContact(contactId: String): Flow<Map<String, AttachmentProgress>> =
        entries
            .map { all -> all.filterValues { it.contactId == contactId }.mapValues { it.value.progress } }
            .distinctUntilChanged()

    fun update(messageId: String, contactId: String, bytesDone: Long, totalBytes: Long, direction: MessageDirection) {
        val entry = Entry(contactId, AttachmentProgress(bytesDone.coerceIn(0L, totalBytes), totalBytes, direction))
        entries.update { it + (messageId to entry) }
        publish()
    }

    fun remove(messageId: String) {
        entries.update { it - messageId }
        publish()
    }

    fun clear() {
        entries.value = emptyMap()
        publish()
    }

    private fun publish() {
        snapshot.value = entries.value.mapValues { it.value.progress }
    }
}
