package ir.vmessenger.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.core.designsystem.component.DeliveryTicksState
import ir.vmessenger.core.designsystem.format.VmDateFormat
import ir.vmessenger.domain.model.ConversationSummary
import ir.vmessenger.domain.model.DeliveryStatus
import ir.vmessenger.domain.model.MessageDirection
import ir.vmessenger.domain.usecase.chat.DeleteConversationUseCase
import ir.vmessenger.domain.usecase.chat.MuteConversationUseCase
import ir.vmessenger.domain.usecase.chat.ObserveChatListUseCase
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The chats tab.
 *
 * Search is a pure in-memory filter over the one observed list (there is no second
 * query), and selection mode is state, not a separate screen. Rows are mapped —
 * timestamps formatted, delivery state turned into ticks — here rather than in
 * composition.
 */
@HiltViewModel
class ChatListViewModel @Inject constructor(
    observeChatList: ObserveChatListUseCase,
    private val muteConversation: MuteConversationUseCase,
    private val deleteConversation: DeleteConversationUseCase,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val searching = MutableStateFlow(false)
    private val selection = MutableStateFlow<ImmutableSet<String>>(persistentSetOf())

    // null is "the database has not answered yet", which is what separates the
    // skeleton from a genuinely empty list.
    private val rows: Flow<List<ChatListRow>?> = observeChatList()
        .map<List<ConversationSummary>, List<ChatListRow>?> { summaries ->
            summaries.map(ConversationSummary::toRow)
        }
        .onStart { emit(null) }

    val uiState: StateFlow<ChatListUiState> = combine(
        rows,
        query,
        searching,
        selection,
    ) { loaded, text, inSearch, selected ->
        ChatListUiState(
            rows = loaded.orEmpty().filter { it.matches(text) }.toImmutableList(),
            query = text,
            searching = inSearch,
            // A row deleted under an open selection must not stay selected.
            selection = selected.filterTo(mutableSetOf()) { id -> loaded.orEmpty().any { it.id == id } }
                .toPersistentSet(),
            loading = loaded == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), ChatListUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSearchOpen() {
        searching.value = true
    }

    fun onSearchClose() {
        searching.value = false
        query.value = ""
    }

    fun onToggleSelection(conversationId: String) {
        val current = selection.value
        selection.value = if (conversationId in current) {
            (current - conversationId).toPersistentSet()
        } else {
            (current + conversationId).toPersistentSet()
        }
    }

    fun onClearSelection() {
        selection.value = persistentSetOf()
    }

    /** Mutes every selected conversation, or unmutes them when they are all muted already. */
    fun onToggleMuteSelected() {
        val ids = selection.value
        val muted = uiState.value.selectionMuted
        selection.value = persistentSetOf()
        viewModelScope.launch {
            ids.forEach { muteConversation(it, !muted) }
        }
    }

    fun onDeleteSelected() {
        val ids = selection.value
        selection.value = persistentSetOf()
        viewModelScope.launch {
            ids.forEach { deleteConversation(it) }
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}

private fun ChatListRow.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return title.contains(needle, ignoreCase = true) || preview?.contains(needle, ignoreCase = true) == true
}

private fun ConversationSummary.toRow(): ChatListRow = ChatListRow(
    id = id,
    contactId = contactId,
    title = contactName,
    seed = IdentitySeed(identityHash),
    preview = preview,
    previewKind = previewKind,
    // Ticks belong to the last message only when it is the user's own.
    ticks = lastStatus?.takeIf { lastDirection == MessageDirection.OUTGOING }?.toTicks(),
    time = if (lastActivityUnixMs > 0L) VmDateFormat.chatListTime(lastActivityUnixMs) else "",
    unreadCount = unreadCount,
    muted = muted,
)

internal fun DeliveryStatus.toTicks(): DeliveryTicksState = when (this) {
    DeliveryStatus.QUEUED -> DeliveryTicksState.QUEUED
    DeliveryStatus.SENT -> DeliveryTicksState.SENT
    DeliveryStatus.DELIVERED -> DeliveryTicksState.DELIVERED
    DeliveryStatus.READ -> DeliveryTicksState.READ
    DeliveryStatus.FAILED -> DeliveryTicksState.FAILED
}
