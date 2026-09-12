package ir.vmessenger.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.vmessenger.domain.model.Contact
import ir.vmessenger.domain.model.ContactRelationshipStatus
import ir.vmessenger.domain.usecase.chat.StartConversationUseCase
import ir.vmessenger.domain.usecase.contact.ObserveContactsUseCase
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One pickable contact. Non-approved contacts stay visible but are not tappable. */
@Immutable
data class NewChatRow(
    val contactId: String,
    val name: String,
    val userHash: String,
    val seed: IdentitySeed,
    val status: ContactRelationshipStatus,
    val blocked: Boolean,
) {
    val canChat: Boolean get() = !blocked && status == ContactRelationshipStatus.APPROVED
}

@Immutable
data class NewChatUiState(
    val rows: ImmutableList<NewChatRow> = persistentListOf(),
    val query: String = "",
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty() && query.isBlank()
    val isNoResults: Boolean get() = !loading && rows.isEmpty() && query.isNotBlank()
}

/**
 * The "new chat" picker: contacts, searchable, tap to open (or create) the conversation.
 * Group creation is a row the screen owns; this ViewModel knows nothing about it.
 */
@HiltViewModel
class NewChatViewModel @Inject constructor(
    observeContacts: ObserveContactsUseCase,
    private val startConversation: StartConversationUseCase,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val opened = MutableStateFlow<String?>(null)

    /** Set once the conversation exists; the screen navigates and calls [onOpenHandled]. */
    val openedConversationId: StateFlow<String?> = opened.asStateFlow()

    private val rows: Flow<List<NewChatRow>?> = observeContacts()
        .map<List<Contact>, List<NewChatRow>?> { contacts -> contacts.map(Contact::toRow).sorted() }
        .onStart { emit(null) }

    val uiState: StateFlow<NewChatUiState> = combine(rows, query) { loaded, text ->
        NewChatUiState(
            rows = loaded.orEmpty().filter { it.matches(text) }.toImmutableList(),
            query = text,
            loading = loaded == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), NewChatUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onContactClick(contactId: String) {
        viewModelScope.launch { opened.value = startConversation(contactId) }
    }

    fun onOpenHandled() {
        opened.value = null
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}

/** Chat-ready contacts first, then alphabetically — the picker's job is to be quick. */
private fun List<NewChatRow>.sorted(): List<NewChatRow> =
    sortedWith(compareByDescending<NewChatRow> { it.canChat }.thenBy { it.name })

private fun NewChatRow.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return name.contains(needle, ignoreCase = true) || userHash.contains(needle, ignoreCase = true)
}

private fun Contact.toRow(): NewChatRow = NewChatRow(
    contactId = id,
    name = displayName,
    userHash = userHash,
    seed = IdentitySeed(identityHash),
    status = relationshipStatus,
    blocked = blocked,
)
