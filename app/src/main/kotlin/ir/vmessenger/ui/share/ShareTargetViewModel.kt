package ir.vmessenger.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.repository.ConversationRepository
import ir.vmessenger.domain.usecase.chat.ObserveChatListUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Picks the destination for content another app shared in.
 *
 * The payload is taken at construction because the store is a one-shot hand-off: emptying it closes
 * the navigation trigger that opened this screen, so backing out cannot reopen it. Images go through
 * the album path, so several shared at once arrive as one group rather than a burst of bubbles.
 */
@HiltViewModel
class ShareTargetViewModel @Inject constructor(
    store: PendingShareStore,
    observeChatList: ObserveChatListUseCase,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val payload: SharePayload? = store.consume()

    val conversations: StateFlow<List<ConversationSummary>> = observeChatList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    private val _sending = MutableStateFlow(false)

    /** True once a destination is picked, so a second tap cannot send the same share twice. */
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    fun onPick(conversationId: String, onSent: (String) -> Unit) {
        val share = payload?.takeUnless { _sending.value } ?: return
        _sending.value = true
        viewModelScope.launch {
            // Files first, then any text: the caption-like line reads better under the media, and
            // the copy happens now, while the activity still holds the sender's read grant.
            if (share.uris.isNotEmpty()) {
                conversationRepository.sendAlbum(conversationId, share.uris)
            }
            share.text?.takeIf { it.isNotBlank() }?.let { text ->
                conversationRepository.sendMessage(conversationId, text)
            }
            onSent(conversationId)
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}
